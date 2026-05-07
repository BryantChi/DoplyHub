package com.gimy.tv.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gimy.tv.data.update.UpdateState
import com.gimy.tv.ui.components.DoplyButton
import com.gimy.tv.ui.theme.*

/**
 * Floating dialog overlay that observes [UpdateViewModel.state] and presents the appropriate
 * UI for each phase of the update flow. Place once at the root of the screen tree (e.g. above
 * NavHost) so it surfaces regardless of which screen the user is on.
 *
 * Mandatory updates: dismiss/back buttons hidden. The user has no path forward except update.
 */
@Composable
fun UpdateOverlay(viewModel: UpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var needsInstallPermission by remember { mutableStateOf(false) }

    // Cold-start check — fires once per Activity lifetime. Silent so a first-launch network
    // hiccup doesn't greet the user with an error dialog. Controller dedupes if already running.
    LaunchedEffect(Unit) { viewModel.check(silent = true) }

    // Auto-launch installer once download finishes (TV: smooths the flow; user can still cancel
    // at the system installer screen). If permission missing, drop into the permission prompt.
    LaunchedEffect(state) {
        if (state is UpdateState.ReadyToInstall && !needsInstallPermission) {
            if (!viewModel.launchInstaller()) {
                needsInstallPermission = true
            }
        }
    }

    when (val s = state) {
        is UpdateState.Available -> AvailableDialog(
            state = s,
            onConfirm = { viewModel.startDownload() },
            onDismiss = if (s.info.isMandatory) null else ({ viewModel.dismiss() }),
        )

        is UpdateState.Downloading -> DownloadingDialog(
            state = s,
            onCancel = if (s.info.isMandatory) null else ({ viewModel.cancelDownload() }),
        )

        is UpdateState.ReadyToInstall -> if (needsInstallPermission) {
            PermissionDialog(
                onGrant = {
                    viewModel.requestInstallPermission()
                    needsInstallPermission = false
                    // Don't dismiss state — user returns to app, taps install again, retries launch
                },
                onLater = if (s.info.isMandatory) null else ({
                    needsInstallPermission = false
                    viewModel.dismiss()
                }),
                onRetry = {
                    if (viewModel.launchInstaller()) needsInstallPermission = false
                },
            )
        } else {
            // Installer launched successfully — state will return to Idle on next launchInstaller()
            // No-op UI, system installer dialog has taken over
        }

        is UpdateState.Error -> ErrorDialog(message = s.message, onClose = { viewModel.dismiss() })

        UpdateState.Idle, UpdateState.Checking, UpdateState.UpToDate -> Unit
    }
}

@Composable
private fun AvailableDialog(
    state: UpdateState.Available,
    onConfirm: () -> Unit,
    onDismiss: (() -> Unit)?,
) {
    AlertDialog(
        onDismissRequest = { onDismiss?.invoke() },
        containerColor = CinemaSurface,
        shape = RoundedCornerShape(12.dp),
        title = {
            Column {
                Text(
                    if (state.info.isMandatory) "需要更新" else "發現新版本",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "v${state.info.currentVersion} → v${state.info.latestVersion}",
                    color = CinemaTextMuted,
                    fontSize = 12.sp,
                )
            }
        },
        text = {
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                if (state.info.isMandatory) {
                    Text(
                        state.info.mandatoryMessage,
                        color = CinemaRed,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Text(
                    state.info.changelog,
                    color = CinemaTextPrimary.copy(0.85f),
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
            }
        },
        confirmButton = {
            DoplyButton(
                onClick = onConfirm,
                containerColor = CinemaRed,
                shape = RoundedCornerShape(6.dp),
            ) { Text("立即更新", color = Color.White, fontSize = 13.sp) }
        },
        dismissButton = onDismiss?.let {
            {
                DoplyButton(
                    onClick = it,
                    containerColor = CinemaSurface,
                    shape = RoundedCornerShape(6.dp),
                ) { Text("稍後", color = Color.White, fontSize = 13.sp) }
            }
        },
    )
}

@Composable
private fun DownloadingDialog(
    state: UpdateState.Downloading,
    onCancel: (() -> Unit)?,
) {
    val pct = if (state.total > 0) (state.downloaded * 100f / state.total).coerceIn(0f, 100f) else 0f
    AlertDialog(
        onDismissRequest = { /* not dismissible by tapping outside */ },
        containerColor = CinemaSurface,
        shape = RoundedCornerShape(12.dp),
        title = { Text("下載中", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                LinearProgressIndicator(
                    progress = { pct / 100f },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = CinemaRed,
                    trackColor = CinemaBase,
                    strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "${"%.1f".format(pct)}% — ${formatSize(state.downloaded)} / ${formatSize(state.total)}",
                    color = CinemaTextMuted,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            if (onCancel != null) {
                DoplyButton(
                    onClick = onCancel,
                    containerColor = CinemaSurface,
                    shape = RoundedCornerShape(6.dp),
                ) { Text("取消", color = Color.White, fontSize = 13.sp) }
            }
        },
    )
}

@Composable
private fun PermissionDialog(
    onGrant: () -> Unit,
    onLater: (() -> Unit)?,
    onRetry: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onLater?.invoke() },
        containerColor = CinemaSurface,
        shape = RoundedCornerShape(12.dp),
        title = { Text("需要授予安裝權限", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Text(
                "請於系統設定中允許 Doply Hub 安裝應用程式，授予後返回此畫面再次點擊「重試安裝」。",
                color = CinemaTextPrimary.copy(0.85f),
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DoplyButton(
                    onClick = onGrant,
                    containerColor = CinemaRed,
                    shape = RoundedCornerShape(6.dp),
                ) { Text("前往設定", color = Color.White, fontSize = 13.sp) }
                DoplyButton(
                    onClick = onRetry,
                    containerColor = CinemaSurface,
                    shape = RoundedCornerShape(6.dp),
                ) { Text("重試安裝", color = Color.White, fontSize = 13.sp) }
            }
        },
        dismissButton = onLater?.let {
            {
                DoplyButton(
                    onClick = it,
                    containerColor = CinemaSurface,
                    shape = RoundedCornerShape(6.dp),
                ) { Text("稍後", color = Color.White, fontSize = 13.sp) }
            }
        },
    )
}

@Composable
private fun ErrorDialog(message: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = CinemaSurface,
        shape = RoundedCornerShape(12.dp),
        title = { Text("更新失敗", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = { Text(message, color = CinemaTextPrimary.copy(0.85f), fontSize = 13.sp) },
        confirmButton = {
            DoplyButton(
                onClick = onClose,
                containerColor = CinemaSurface,
                shape = RoundedCornerShape(6.dp),
            ) { Text("關閉", color = Color.White, fontSize = 13.sp) }
        },
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes < 1024 -> "${bytes} B"
    bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}
