package com.gimy.tv.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.gimy.tv.data.update.UpdateState
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.displayName
import com.gimy.tv.ui.components.DoplyButton
import com.gimy.tv.ui.components.PinInputDialog
import com.gimy.tv.ui.favorites.PageHeader
import com.gimy.tv.ui.theme.*
import com.gimy.tv.ui.update.UpdateViewModel
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: UpdateViewModel = hiltViewModel(),
    settingsVm: SettingsViewModel = hiltViewModel(),
    adultVm: AdultContentViewModel = hiltViewModel(),
) {
    val dims = LocalDimensions.current
    val ctx = LocalContext.current
    val state by vm.state.collectAsState()
    val versionName = remember { currentVersionName(ctx) }
    val enabledSources by settingsVm.enabledSources.collectAsState()
    val adultEnabled by adultVm.enabled.collectAsState()
    val pinRequired by adultVm.pinRequired.collectAsState()
    val pinHash by adultVm.pinHash.collectAsState()
    val adultPlusEnabled by adultVm.adultPlusEnabled.collectAsState()
    var showSetPinDialog by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var pinSetupStep by remember { mutableStateOf(0) }       // 0 = entering, 1 = confirming
    var firstPin by remember { mutableStateOf("") }
    var pinErrorMsg by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CinemaBase, CinemaBlack)))
    ) {
        PageHeader("設定", onBack)

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = dims.screenHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                SettingSection(title = "關於") {
                    InfoRow("應用名稱", "Doply Hub")
                    InfoRow("版本", "v$versionName")
                }
            }

            item {
                SettingSection(title = "更新") {
                    Text(
                        statusLine(state, versionName),
                        color = CinemaTextMuted,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val checking = state is UpdateState.Checking
                        val downloading = state is UpdateState.Downloading
                        DoplyButton(
                            onClick = { vm.check() },
                            enabled = !checking && !downloading,
                            containerColor = CinemaRed,
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                if (checking) "檢查中…" else "檢查更新",
                                color = Color.White,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }

            item {
                SettingSection(title = "來源管理") {
                    Text(
                        "停用的來源不會出現在搜尋、首頁更多來源、詳情頁的跨來源切換。",
                        color = CinemaTextMuted, fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    SourceType.values().forEach { type ->
                        SourceToggleRow(
                            label = type.displayName,
                            enabled = type in enabledSources,
                            isPrimary = type == SourceType.GIMYTV,
                            onToggle = { newValue ->
                                // Guard: never let the user disable every source — block the
                                // last toggle-off so search isn't bricked by a stray click.
                                if (!newValue && enabledSources.size <= 1 && type in enabledSources) return@SourceToggleRow
                                settingsVm.toggleSource(type, newValue)
                            },
                        )
                    }
                }
            }

            item {
                SettingSection(title = "成人內容") {
                    Text(
                        "啟用後可在「分類瀏覽」存取 18+ 區。建議搭配 PIN 鎖避免家人誤觸。",
                        color = CinemaTextMuted, fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    SourceToggleRow(
                        label = "顯示成人內容",
                        enabled = adultEnabled,
                        isPrimary = false,
                        onToggle = { adultVm.setEnabled(it) },
                    )
                    if (adultEnabled) {
                        SourceToggleRow(
                            label = "啟用 PIN 鎖",
                            enabled = pinRequired && pinHash != null,
                            isPrimary = false,
                            onToggle = { newValue ->
                                if (newValue) {
                                    if (pinHash == null) {
                                        // Need to set a PIN first; opening the dialog will save+enable on completion
                                        firstPin = ""
                                        pinSetupStep = 0
                                        pinErrorMsg = null
                                        showSetPinDialog = true
                                    } else {
                                        adultVm.setPinRequired(true)
                                    }
                                } else {
                                    adultVm.setPinRequired(false)
                                    adultVm.clearPin()
                                }
                            },
                        )
                        if (pinHash != null) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                DoplyButton(
                                    onClick = {
                                        firstPin = ""
                                        pinSetupStep = 0
                                        pinErrorMsg = null
                                        showSetPinDialog = true
                                    },
                                    containerColor = CinemaSurface,
                                    shape = RoundedCornerShape(6.dp),
                                ) { Text("變更 PIN", color = CinemaTextPrimary, fontSize = 12.sp) }
                            }
                        }
                        // Phase 6 — Advanced adult sources gate. Locked behind PIN being set
                        // (we don't want a fresh-install-then-toggle path with no friction).
                        SourceToggleRow(
                            label = "進階成人來源（jable / xnxx / 5278）",
                            enabled = adultPlusEnabled,
                            isPrimary = false,
                            onToggle = { newValue ->
                                if (newValue && pinHash == null) {
                                    // Force user to set a PIN first — disclaimer happens implicitly
                                    firstPin = ""
                                    pinSetupStep = 0
                                    pinErrorMsg = null
                                    showSetPinDialog = true
                                } else {
                                    adultVm.setAdultPlusEnabled(newValue)
                                }
                            },
                        )
                        if (adultPlusEnabled) {
                            Text(
                                "⚠ 內容為第三方聚合站，未經授權；使用須自行承擔法律責任。",
                                color = CinemaRed.copy(0.8f), fontSize = 11.sp,
                                modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        DoplyButton(
                            onClick = { showResetConfirm = true },
                            containerColor = CinemaSurface,
                            shape = RoundedCornerShape(6.dp),
                        ) { Text("重置成人內容設定", color = CinemaRed, fontSize = 12.sp) }
                    }
                }
            }
        }
    }

    if (showSetPinDialog) {
        PinInputDialog(
            title = if (pinSetupStep == 0) "設定 PIN" else "確認 PIN",
            subtitle = if (pinSetupStep == 0) "請輸入 4 位數字" else "再次輸入相同的 4 位數字",
            failureMessage = pinErrorMsg,
            onPinComplete = { input ->
                if (pinSetupStep == 0) {
                    firstPin = input
                    pinSetupStep = 1
                    pinErrorMsg = null
                } else {
                    if (input == firstPin) {
                        adultVm.savePin(input)
                        adultVm.setPinRequired(true)
                        showSetPinDialog = false
                        firstPin = ""
                        pinSetupStep = 0
                        pinErrorMsg = null
                    } else {
                        pinErrorMsg = "兩次輸入不一致，請從頭再來"
                        firstPin = ""
                        pinSetupStep = 0
                    }
                }
            },
            onDismiss = {
                showSetPinDialog = false
                firstPin = ""
                pinSetupStep = 0
                pinErrorMsg = null
            },
        )
    }

    if (showResetConfirm) {
        Dialog(onDismissRequest = { showResetConfirm = false }) {
            Column(
                Modifier
                    .background(CinemaCard, RoundedCornerShape(12.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("重置成人內容設定？", color = CinemaTextPrimary, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text("會清空 PIN 與所有成人內容偏好，恢復為預設關閉狀態。",
                    color = CinemaTextMuted, fontSize = 12.sp)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DoplyButton(
                        onClick = { showResetConfirm = false },
                        containerColor = CinemaSurface,
                        shape = RoundedCornerShape(6.dp),
                    ) { Text("取消", color = CinemaTextPrimary, fontSize = 13.sp) }
                    DoplyButton(
                        onClick = {
                            adultVm.resetAll()
                            showResetConfirm = false
                        },
                        containerColor = CinemaRed,
                        shape = RoundedCornerShape(6.dp),
                    ) { Text("確認重置", color = Color.White, fontSize = 13.sp) }
                }
            }
        }
    }
}

@Composable
private fun SourceToggleRow(
    label: String,
    enabled: Boolean,
    isPrimary: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = CinemaTextPrimary, fontSize = 13.sp,
                    fontWeight = FontWeight.Medium)
                if (isPrimary) {
                    Spacer(Modifier.width(8.dp))
                    Text("主來源", color = CinemaRed, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(CinemaRed.copy(0.15f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp))
                }
            }
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = CinemaRed,
                uncheckedThumbColor = CinemaTextMuted,
                uncheckedTrackColor = CinemaSurface,
            ),
        )
    }
}

@Composable
private fun SettingSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, color = CinemaTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier.fillMaxWidth().background(CinemaSurface.copy(0.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            content = content,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = CinemaTextMuted, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = CinemaTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

private fun statusLine(state: UpdateState, current: String): String = when (state) {
    UpdateState.Idle -> "目前版本 v$current"
    UpdateState.Checking -> "正在檢查更新…"
    UpdateState.UpToDate -> "已是最新版本"
    is UpdateState.Available -> "發現新版本 v${state.info.latestVersion}"
    is UpdateState.Downloading -> {
        val pct = if (state.total > 0) (state.downloaded * 100f / state.total).coerceIn(0f, 100f) else 0f
        "下載中 ${"%.0f".format(pct)}%"
    }
    is UpdateState.ReadyToInstall -> "下載完成，等待安裝…"
    is UpdateState.Error -> "錯誤：${state.message}"
}

private fun currentVersionName(ctx: Context): String = runCatching {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
}.getOrDefault("?")
