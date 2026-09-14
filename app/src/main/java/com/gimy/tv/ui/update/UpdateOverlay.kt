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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gimy.tv.domain.model.UpdateState
import com.gimy.tv.ui.components.DoplyButton
import com.gimy.tv.ui.theme.*
import com.gimy.tv.R
import androidx.compose.ui.res.stringResource

/**
 * Floating dialog overlay that observes [UpdateViewModel.state] and presents the appropriate
 * UI for each phase of the update flow. Place once at the root of the screen tree (e.g. above
 * NavHost) so it surfaces regardless of which screen the user is on.
 *
 * Mandatory updates: dismiss/back buttons hidden. The user has no path forward except update.
 */
@Composable
fun UpdateOverlay(viewModel: UpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var needsInstallPermission by remember { mutableStateOf(false) }
    // 「重試安裝」按下去卻還是沒權限時，要讓畫面有反應——否則按鈕看起來完全沒作用。
    var retryFailed by remember { mutableStateOf(false) }
    var settingsUnavailable by remember { mutableStateOf(false) }

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
                    // 這裡刻意不把 needsInstallPermission 設回 false：state 仍是 ReadyToInstall，
                    // 而自動安裝的 LaunchedEffect 只以 state 當 key，不會因為旗標變動重跑。
                    // 一旦把對話框收掉就會落到下面的 no-op 分支，使用者從設定頁回來後既沒有
                    // 對話框也沒有安裝動作，更新就永久卡死。
                    settingsUnavailable = !viewModel.requestInstallPermission()
                    retryFailed = false
                },
                onLater = if (s.info.isMandatory) null else ({
                    needsInstallPermission = false
                    viewModel.dismiss()
                }),
                onRetry = {
                    if (viewModel.launchInstaller()) {
                        needsInstallPermission = false
                        retryFailed = false
                    } else {
                        retryFailed = true
                    }
                },
                retryFailed = retryFailed,
                settingsUnavailable = settingsUnavailable,
            )
        } else {
            // 安裝器已經被拉到前景，這個對話框會被它蓋住；使用者若在系統安裝畫面按返回
            // 或安裝失敗，回到這裡就看得到它。這個入口是必要的——launchInstaller 不再把
            // 狀態收回 Idle，沒有它的話使用者會停在空畫面，那份下載好的 APK 再也裝不了。
            ReadyDialog(
                version = s.info.latestVersion.toString(),
                onInstall = { if (!viewModel.launchInstaller()) needsInstallPermission = true },
                onLater = if (s.info.isMandatory) null else ({ viewModel.dismiss() }),
            )
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
                    stringResource(if (state.info.isMandatory) R.string.update_required else R.string.update_found),
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
                    stringResource(R.string.update_install_hint),
                    color = CinemaTextMuted,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(10.dp))
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
            ) { Text(stringResource(R.string.update_now), color = Color.White, fontSize = 13.sp) }
        },
        dismissButton = onDismiss?.let {
            {
                DoplyButton(
                    onClick = it,
                    containerColor = CinemaSurface,
                    shape = RoundedCornerShape(6.dp),
                ) { Text(stringResource(R.string.common_later), color = Color.White, fontSize = 13.sp) }
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
        title = { Text(stringResource(R.string.update_downloading), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
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
                ) { Text(stringResource(R.string.common_cancel), color = Color.White, fontSize = 13.sp) }
            }
        },
    )
}

@Composable
private fun ReadyDialog(
    version: String,
    onInstall: () -> Unit,
    onLater: (() -> Unit)?,
) {
    AlertDialog(
        onDismissRequest = { onLater?.invoke() },
        containerColor = CinemaSurface,
        shape = RoundedCornerShape(12.dp),
        title = { Text(stringResource(R.string.update_downloaded), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Text(
                stringResource(R.string.update_downloaded_desc, version),
                color = CinemaTextPrimary.copy(0.85f),
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
        },
        confirmButton = {
            DoplyButton(
                onClick = onInstall,
                containerColor = CinemaRed,
                shape = RoundedCornerShape(6.dp),
            ) { Text(stringResource(R.string.update_install_now), color = Color.White, fontSize = 13.sp) }
        },
        dismissButton = onLater?.let {
            {
                DoplyButton(
                    onClick = it,
                    containerColor = CinemaSurface,
                    shape = RoundedCornerShape(6.dp),
                ) { Text(stringResource(R.string.common_later), color = Color.White, fontSize = 13.sp) }
            }
        },
    )
}

@Composable
private fun PermissionDialog(
    onGrant: () -> Unit,
    onLater: (() -> Unit)?,
    onRetry: () -> Unit,
    retryFailed: Boolean = false,
    settingsUnavailable: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = { onLater?.invoke() },
        containerColor = CinemaSurface,
        shape = RoundedCornerShape(12.dp),
        title = { Text(stringResource(R.string.update_need_permission), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    stringResource(R.string.update_permission_desc),
                    color = CinemaTextPrimary.copy(0.85f),
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
                if (settingsUnavailable) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.update_permission_no_page),
                        color = CinemaRed,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                } else if (retryFailed) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.update_permission_denied),
                        color = CinemaRed,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DoplyButton(
                    onClick = onGrant,
                    containerColor = CinemaRed,
                    shape = RoundedCornerShape(6.dp),
                ) { Text(stringResource(R.string.update_goto_settings), color = Color.White, fontSize = 13.sp) }
                DoplyButton(
                    onClick = onRetry,
                    containerColor = CinemaSurface,
                    shape = RoundedCornerShape(6.dp),
                ) { Text(stringResource(R.string.update_retry_install), color = Color.White, fontSize = 13.sp) }
            }
        },
        dismissButton = onLater?.let {
            {
                DoplyButton(
                    onClick = it,
                    containerColor = CinemaSurface,
                    shape = RoundedCornerShape(6.dp),
                ) { Text(stringResource(R.string.common_later), color = Color.White, fontSize = 13.sp) }
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
        title = { Text(stringResource(R.string.update_failed), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = { Text(message, color = CinemaTextPrimary.copy(0.85f), fontSize = 13.sp) },
        confirmButton = {
            DoplyButton(
                onClick = onClose,
                containerColor = CinemaSurface,
                shape = RoundedCornerShape(6.dp),
            ) { Text(stringResource(R.string.common_close), color = Color.White, fontSize = 13.sp) }
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
