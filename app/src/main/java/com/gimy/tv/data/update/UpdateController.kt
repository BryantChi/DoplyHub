package com.gimy.tv.data.update

import com.gimy.tv.domain.repository.AppUpdater
import com.gimy.tv.domain.model.UpdateState
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.gimy.tv.data.network.CloudflareInterceptor
import com.gimy.tv.data.network.SearchRateLimitInterceptor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central state holder + orchestrator for the in-app update flow.
 *
 * Lifecycle (state transitions):
 *   Idle → Checking → (UpToDate | Available | Error)
 *   Available → Downloading(progress) → ReadyToInstall → Idle (after launching system installer)
 *   Available → Dismissed (user picked "later", non-mandatory only)
 */
@Singleton
class UpdateController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updateService: AppUpdateService,
    private val okHttpClient: OkHttpClient,
) : AppUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** APK 下載專用的 client。
     *
     *  主 client 有 `callTimeout(20s)`——那是為了讓十二處阻塞 `execute()` 的抓取有個
     *  絕對上限（理由見 NetworkModule），但它涵蓋整個 call 包含 body 讀取，套在
     *  幾 MB 的 APK 下載上就變成「超過 20 秒一律失敗」。這裡只為下載解除，不動全域。
     *
     *  順帶收掉三樣對下載沒有意義的東西：20MB 共用磁碟快取、CloudflareInterceptor、
     *  以及會對每個成功回應 peek 8KB 當字串解的 SearchRateLimitInterceptor。
     *  readTimeout 也縮短——取消時要等當前這次 read 回來才看得到 ensureActive，
     *  繼承來的 15 秒太久。
     *
     *  用 newBuilder 是刻意的：共用同一個連線池與 dispatcher，不會多開資源。 */
    private val downloadClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            // connectTimeout 一定要一起放寬——它是繼承主 client 的 8 秒，而備援來源
            // release-assets.githubusercontent.com 在台灣建連線經常超過那個數字，
            // 於是「CDN 失敗就退回 GitHub」這條路等於不存在，照樣 timeout。
            // 下載是背景進行、使用者看得到進度，慢一點沒關係，失敗才是問題。
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .cache(null)
            .apply {
                // 只拿掉這兩個具名的；不可以用 clear()，那會把 NetworkModule 裡
                // 設定 User-Agent 的匿名 interceptor 一起清掉。
                interceptors().removeAll {
                    it is CloudflareInterceptor || it is SearchRateLimitInterceptor
                }
            }
            .build()
    }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var downloadJob: Job? = null

    /** 每次下載的身分序號，取消或重新開始時遞增。
     *
     *  下載迴圈是阻塞的 okio read，`Job.cancel()` 之後那條執行緒還會活一段時間，
     *  期間仍會吐出數十次進度回呼（節流 200ms）。單看狀態擋不住——`cancelDownload`
     *  把狀態設回 Available，而首次回呼本來就要從 Available 轉成 Downloading，
     *  兩者長得一樣。所以改用序號認身分：不是當前這輪的回呼一律不准碰狀態。
     *  跨執行緒讀寫（回呼在 IO、取消在呼叫端），必須 volatile。 */
    @Volatile
    private var downloadToken = 0L

    /**
     * @param silent when true, network errors are swallowed back to Idle (used by cold-start check
     *               so first launch doesn't surface a "更新失敗" dialog when the user's just offline).
     *               Manual checks from the settings page should pass false to surface the error.
     */
    override fun checkForUpdate(silent: Boolean) {
        // ReadyToInstall 也要擋：APK 已經下載好了，這時候去查更新會把狀態洗成 Checking，
        // 那份檔案的參照就此遺失，使用者得整包重下。
        if (_state.value is UpdateState.Checking ||
            _state.value is UpdateState.Downloading ||
            _state.value is UpdateState.ReadyToInstall
        ) return
        _state.value = UpdateState.Checking
        scope.launch {
            when (val info = updateService.checkForUpdate()) {
                is UpdateInfo.UpToDate -> {
                    // 已經是最新版，之前下載的 APK 留著也沒用了。挑這個時機清是因為
                    // 安裝成功後 App 會重啟並跑一次冷啟動檢查，剛好落在這裡；
                    // 不能在 launchInstaller 之後就刪——那時系統安裝器還在讀這個檔。
                    clearDownloadedApks()
                    _state.value = UpdateState.UpToDate
                }
                is UpdateInfo.Available -> _state.value = UpdateState.Available(info)
                is UpdateInfo.Error -> _state.value =
                    if (silent) UpdateState.Idle else UpdateState.Error(info.message)
            }
        }
    }

    override fun dismiss() {
        val current = _state.value
        // Mandatory updates can't be dismissed — caller should not even surface a dismiss button.
        if (current is UpdateState.Available && current.info.isMandatory) return
        _state.value = UpdateState.Idle
    }

    override fun startDownload() {
        val available = (_state.value as? UpdateState.Available) ?: return
        downloadJob?.cancel()
        val token = ++downloadToken
        // 立刻切到 Downloading。以前要等第一次進度回呼（connect 最久 8 秒，再加 200ms
        // 節流）狀態才會變，這段空窗期裡「發現新版本」對話框還在畫面上，遙控器重按
        // 確認鍵就會再開一條下載——而舊那條當時還取消不掉。
        _state.value = UpdateState.Downloading(available.info, 0L, available.info.sizeBytes)
        downloadJob = scope.launch {
            try {
                val apkFile = downloadApk(available.info) { downloaded, total ->
                    if (token != downloadToken) return@downloadApk
                    _state.update { prev ->
                        if (prev is UpdateState.Downloading) prev.copy(downloaded = downloaded, total = total)
                        else prev
                    }
                }
                if (token != downloadToken) return@launch
                _state.value = UpdateState.ReadyToInstall(available.info, apkFile)
            } catch (e: CancellationException) {
                // 取消不是錯誤。以前被下面那個 catch 一起接走，使用者按了取消之後
                // 會看到「下載失敗: StandaloneCoroutine was cancelled」。
                throw e
            } catch (e: Exception) {
                if (token == downloadToken) {
                    _state.value = UpdateState.Error("下載失敗: ${e.message ?: "未知錯誤"}")
                }
            }
        }
    }

    override fun cancelDownload() {
        // 先讓仍在路上的回呼失效，再取消 job：阻塞中的 read 停不下來，
        // 這行是「畫面不會被復活成下載中」的唯一保證。
        downloadToken++
        downloadJob?.cancel()
        downloadJob = null
        val available = (_state.value as? UpdateState.Downloading)?.info
        _state.value = if (available != null) UpdateState.Available(available) else UpdateState.Idle
    }

    /** Launch the system installer for an already-downloaded APK.
     *  Returns false if "install unknown apps" permission is missing — caller should call
     *  [requestInstallPermission] then retry.
     *
     *  沒有 APK 可裝時也回 false：以前回 true（宣稱成功），於是「重試安裝」會把權限
     *  對話框收掉卻什麼都沒裝，看起來就是按了沒反應。 */
    override fun launchInstaller(): Boolean {
        val ready = (_state.value as? UpdateState.ReadyToInstall) ?: return false
        if (!context.packageManager.canRequestPackageInstalls()) return false

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            ready.apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
        // 這裡刻意「不」把狀態收回 Idle。使用者在系統安裝畫面按返回、或安裝失敗時，
        // 狀態一旦是 Idle，那份已經下載好的 APK 就再也接不回來，只能整包重下。
        // 保留 ReadyToInstall 讓呼叫端可以原地重試；要收掉由使用者按「稍後」(dismiss)。
        return true
    }

    /** 開啟系統設定讓使用者授予「安裝未知應用程式」。Android 8+ 只需授予一次。
     *
     *  回傳 false 代表這台機器上找不到任何可開的設定頁。ACTION_MANAGE_UNKNOWN_APP_SOURCES
     *  在不少 Android TV（Leanback 設定）上根本沒有對應的 Activity，原本無條件
     *  startActivity 會直接丟 ActivityNotFoundException 讓 App 掛掉，所以逐個 fallback
     *  並讓呼叫端能把「請自己去設定裡開」講給使用者聽。 */
    override fun requestInstallPermission(): Boolean {
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }

    /** 清掉 cacheDir 裡殘留的 APK。 */
    private fun clearDownloadedApks() {
        File(context.cacheDir, "apk").listFiles()?.forEach { it.delete() }
    }

    /**
     * 先試主要來源（CDN），失敗才退回 GitHub。
     *
     * 取消不重試——那是使用者自己按的。其餘失敗（連不上、CDN 還沒同步到這個 tag、
     * 落檔大小對不上）都值得換一條線再試一次，畢竟更新失敗的代價是使用者完全裝不了。
     */
    private suspend fun downloadApk(
        info: UpdateInfo.Available,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File {
        return try {
            downloadApkFrom(info, info.downloadUrl, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val fallback = info.fallbackDownloadUrl ?: throw e
            android.util.Log.w("UpdateDownload", "主要來源失敗，改用備援：$fallback", e)
            downloadApkFrom(info, fallback, onProgress)
        }
    }

    private suspend fun downloadApkFrom(
        info: UpdateInfo.Available,
        url: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "apk").apply { mkdirs() }
        // Clear stale APKs before downloading new one
        clearDownloadedApks()

        val target = File(dir, "DoplyHub-v${info.latestVersion}.apk")
        val req = Request.Builder().url(url).get().build()
        try {
            downloadClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body ?: error("空白回應")
                val total = if (body.contentLength() > 0) body.contentLength() else info.sizeBytes
                target.sink().buffer().use { sink ->
                    body.source().use { source ->
                        val buf = okio.Buffer()
                        var totalRead = 0L
                        var lastNotify = 0L
                        while (true) {
                            // 阻塞的 read 吃不到 coroutine 取消，每輪主動檢查一次。
                            // 最壞的延遲是一次 read 回來的時間（受 readTimeout 約束），
                            // 但至少不會像以前那樣整條下載跑到完。
                            currentCoroutineContext().ensureActive()
                            val read = source.read(buf, 64 * 1024)
                            if (read == -1L) break
                            sink.write(buf, read)
                            totalRead += read
                            // Throttle progress callbacks to ~5/s
                            val now = System.currentTimeMillis()
                            if (now - lastNotify > 200) {
                                onProgress(totalRead, total)
                                lastNotify = now
                            }
                        }
                        onProgress(totalRead, total)
                    }
                }
                // sink 關掉之後落檔大小才算數。對不上就是下載被截斷（連線斷了但沒拋
                // 例外、或磁碟寫不下）——這種半截 APK 丟給系統安裝器，使用者只會看到
                // 「解析套件時發生問題」，完全看不出是下載壞了。這裡拋出去，交給外層
                // catch 統一刪檔。
                if (total > 0 && target.length() != total) {
                    error("下載不完整（${target.length()} / $total bytes）")
                }
            }
        } catch (e: Throwable) {
            // 取消或失敗都別留半截檔。目錄雖然會在下次下載開頭清掉，但在那之前
            // 這個檔名看起來就是「已經下載好的那一版」，白佔 cacheDir 也容易誤判。
            target.delete()
            throw e
        }
        target
    }
}
