package com.gimy.tv.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

    /**
     * @param silent when true, network errors are swallowed back to Idle (used by cold-start check
     *               so first launch doesn't surface a "更新失敗" dialog when the user's just offline).
     *               Manual checks from the settings page should pass false to surface the error.
     */
    fun checkForUpdate(silent: Boolean = false) {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
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
        downloadJob = scope.launch {
            try {
                val apkFile = downloadApk(available.info) { downloaded, total ->
                    _state.update { prev ->
                        if (prev is UpdateState.Downloading) prev.copy(downloaded = downloaded, total = total)
                        else UpdateState.Downloading(available.info, downloaded, total)
                    }
                }
                _state.value = UpdateState.ReadyToInstall(available.info, apkFile)
            } catch (e: Exception) {
                _state.value = UpdateState.Error("下載失敗: ${e.message ?: "未知錯誤"}")
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        val available = (_state.value as? UpdateState.Downloading)?.info
        _state.value = if (available != null) UpdateState.Available(available) else UpdateState.Idle
    }

    /** Launch the system installer for an already-downloaded APK.
     *  Returns false if "install unknown apps" permission is missing — caller should call
     *  [requestInstallPermission] then retry. */
    fun launchInstaller(): Boolean {
        val ready = (_state.value as? UpdateState.ReadyToInstall) ?: return true
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
        // Reset state — the installer takes over from here.
        _state.value = UpdateState.Idle
        return true
    }

    /** Open system settings so the user can grant "install unknown apps" for this app.
     *  Required once on Android 8+; persists per-app afterwards. */
    fun requestInstallPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
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
