package com.gimy.tv.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

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
    fun checkForUpdate(silent: Boolean = false) {
        // ReadyToInstall 也要擋：APK 已經下載好了，這時候去查更新會把狀態洗成 Checking，
        // 那份檔案的參照就此遺失，使用者得整包重下。
        if (_state.value is UpdateState.Checking ||
            _state.value is UpdateState.Downloading ||
            _state.value is UpdateState.ReadyToInstall
        ) return
        _state.value = UpdateState.Checking
        scope.launch {
            when (val info = updateService.checkForUpdate()) {
                is UpdateInfo.UpToDate -> _state.value = UpdateState.UpToDate
                is UpdateInfo.Available -> _state.value = UpdateState.Available(info)
                is UpdateInfo.Error -> _state.value =
                    if (silent) UpdateState.Idle else UpdateState.Error(info.message)
            }
        }
    }

    fun dismiss() {
        val current = _state.value
        // Mandatory updates can't be dismissed — caller should not even surface a dismiss button.
        if (current is UpdateState.Available && current.info.isMandatory) return
        _state.value = UpdateState.Idle
    }

    fun startDownload() {
        val available = (_state.value as? UpdateState.Available) ?: return
        downloadJob?.cancel()
        val token = ++downloadToken
        downloadJob = scope.launch {
            try {
                val apkFile = downloadApk(available.info) { downloaded, total ->
                    if (token != downloadToken) return@downloadApk
                    _state.update { prev ->
                        when (prev) {
                            is UpdateState.Downloading -> prev.copy(downloaded = downloaded, total = total)
                            // 首次回呼時狀態還是 Available，在這裡完成轉換。
                            is UpdateState.Available -> UpdateState.Downloading(available.info, downloaded, total)
                            else -> prev
                        }
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

    fun cancelDownload() {
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
    fun launchInstaller(): Boolean {
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
    fun requestInstallPermission(): Boolean {
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

    private suspend fun downloadApk(
        info: UpdateInfo.Available,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "apk").apply { mkdirs() }
        // Clear stale APKs before downloading new one
        dir.listFiles()?.forEach { it.delete() }

        val target = File(dir, "DoplyHub-v${info.latestVersion}.apk")
        val req = Request.Builder().url(info.downloadUrl).get().build()
        try {
            okHttpClient.newCall(req).execute().use { resp ->
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

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data object UpToDate : UpdateState()
    data class Available(val info: UpdateInfo.Available) : UpdateState()
    data class Downloading(val info: UpdateInfo.Available, val downloaded: Long, val total: Long) : UpdateState()
    data class ReadyToInstall(val info: UpdateInfo.Available, val apkFile: File) : UpdateState()
    data class Error(val message: String) : UpdateState()
}
