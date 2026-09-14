package com.gimy.tv.ui.settings

import com.gimy.tv.domain.model.EndpointHealth
import com.gimy.tv.domain.model.EndpointHealthStatus
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gimy.tv.domain.model.UpdateState
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.displayName
import com.gimy.tv.ui.components.DoplyButton
import com.gimy.tv.ui.components.PinInputDialog
import com.gimy.tv.ui.favorites.PageHeader
import com.gimy.tv.ui.theme.*
import com.gimy.tv.ui.update.UpdateViewModel
import kotlinx.coroutines.delay
import com.gimy.tv.R
import androidx.compose.ui.res.stringResource

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: UpdateViewModel = hiltViewModel(),
    settingsVm: SettingsViewModel = hiltViewModel(),
    adultVm: AdultContentViewModel = hiltViewModel(),
) {
    val dims = LocalDimensions.current
    val ctx = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val versionName = remember { currentVersionName(ctx) }
    val enabledSources by settingsVm.enabledSources.collectAsStateWithLifecycle()
    val endpointHealth by settingsVm.endpointHealth.collectAsStateWithLifecycle()
    val cacheClearing by settingsVm.cacheClearing.collectAsStateWithLifecycle()
    val cacheClearResult by settingsVm.cacheClearResult.collectAsStateWithLifecycle()
    val adultEnabled by adultVm.enabled.collectAsStateWithLifecycle()
    val pinRequired by adultVm.pinRequired.collectAsStateWithLifecycle()
    val pinHash by adultVm.pinHash.collectAsStateWithLifecycle()
    val adultPlusEnabled by adultVm.adultPlusEnabled.collectAsStateWithLifecycle()
    var showSetPinDialog by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var pinSetupStep by remember { mutableStateOf(0) }       // 0 = entering, 1 = confirming
    var firstPin by remember { mutableStateOf("") }
    var pinErrorMsg by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CinemaBase, CinemaBlack)))
    ) {
        PageHeader(stringResource(R.string.settings_title), onBack)

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = dims.screenHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                SettingSection(title = stringResource(R.string.settings_about)) {
                    InfoRow(stringResource(R.string.settings_app_name_label), stringResource(R.string.app_name))
                    InfoRow(stringResource(R.string.settings_version), stringResource(R.string.settings_version_value, versionName))
                }
            }

            item {
                SettingSection(title = stringResource(R.string.settings_update)) {
                    Text(
                        statusLine(state, versionName),
                        color = CinemaTextMuted,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val checking = state is UpdateState.Checking
                        val downloading = state is UpdateState.Downloading
                        // 已經下載完成時也不給按：查更新會把狀態洗掉，那份 APK 就得重下。
                        val readyToInstall = state is UpdateState.ReadyToInstall
                        DoplyButton(
                            onClick = { vm.check() },
                            enabled = !checking && !downloading && !readyToInstall,
                            containerColor = CinemaRed,
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                stringResource(if (checking) R.string.settings_checking else R.string.settings_check_update),
                                color = Color.White,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }

            item {
                SettingSection(title = stringResource(R.string.settings_storage)) {
                    Text(
                        stringResource(R.string.settings_clear_cache_desc),
                        color = CinemaTextMuted, fontSize = 12.sp, lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    DoplyButton(
                        onClick = { settingsVm.clearCache() },
                        enabled = !cacheClearing,
                        containerColor = CinemaRed,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            stringResource(if (cacheClearing) R.string.settings_clearing else R.string.settings_clear_cache),
                            color = Color.White, fontSize = 13.sp,
                        )
                    }
                    cacheClearResult?.let { msg ->
                        Spacer(Modifier.height(10.dp))
                        Text(msg, color = CinemaTextMuted, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }

            item {
                SettingSection(title = stringResource(R.string.settings_sources)) {
                    Text(
                        stringResource(R.string.settings_sources_desc),
                        color = CinemaTextMuted, fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    SourceType.values().forEach { type ->
                        SourceToggleRow(
                            label = type.displayName,
                            enabled = type in enabledSources,
                            isPrimary = type == SourceType.GIMYTV,
                            health = endpointHealth[type],
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
                SettingSection(title = stringResource(R.string.settings_adult)) {
                    Text(
                        stringResource(R.string.settings_adult_desc),
                        color = CinemaTextMuted, fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    SourceToggleRow(
                        label = stringResource(R.string.settings_adult_show),
                        enabled = adultEnabled,
                        isPrimary = false,
                        onToggle = { adultVm.setEnabled(it) },
                    )
                    if (adultEnabled) {
                        SourceToggleRow(
                            label = stringResource(R.string.settings_pin_enable),
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
                                ) { Text(stringResource(R.string.settings_pin_change), color = CinemaTextPrimary, fontSize = 12.sp) }
                            }
                        }
                        // Phase 6 — Advanced adult sources gate. Locked behind PIN being set
                        // (we don't want a fresh-install-then-toggle path with no friction).
                        SourceToggleRow(
                            label = stringResource(R.string.settings_adult_plus),
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
                                stringResource(R.string.settings_adult_plus_warn),
                                color = CinemaRed.copy(0.8f), fontSize = 11.sp,
                                modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        DoplyButton(
                            onClick = { showResetConfirm = true },
                            containerColor = CinemaSurface,
                            shape = RoundedCornerShape(6.dp),
                        ) { Text(stringResource(R.string.settings_adult_reset), color = CinemaRed, fontSize = 12.sp) }
                    }
                }
            }
        }
    }

    if (showSetPinDialog) {
        // onPinComplete 不是 Composable，先在組合階段把文案讀出來
        val pinMismatchText = stringResource(R.string.settings_pin_mismatch)
        PinInputDialog(
            title = stringResource(if (pinSetupStep == 0) R.string.settings_pin_set else R.string.settings_pin_confirm),
            subtitle = stringResource(if (pinSetupStep == 0) R.string.settings_pin_enter_4 else R.string.settings_pin_repeat_4),
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
                        pinErrorMsg = pinMismatchText
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
                Text(stringResource(R.string.settings_adult_reset_title), color = CinemaTextPrimary, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.settings_adult_reset_desc),
                    color = CinemaTextMuted, fontSize = 12.sp)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DoplyButton(
                        onClick = { showResetConfirm = false },
                        containerColor = CinemaSurface,
                        shape = RoundedCornerShape(6.dp),
                    ) { Text(stringResource(R.string.common_cancel), color = CinemaTextPrimary, fontSize = 13.sp) }
                    DoplyButton(
                        onClick = {
                            adultVm.resetAll()
                            showResetConfirm = false
                        },
                        containerColor = CinemaRed,
                        shape = RoundedCornerShape(6.dp),
                    ) { Text(stringResource(R.string.settings_adult_reset_confirm), color = Color.White, fontSize = 13.sp) }
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
    /** Only the source-management rows have a probe result; the adult toggles reuse this
     *  row and pass nothing. */
    health: com.gimy.tv.domain.model.EndpointHealth? = null,
    onToggle: (Boolean) -> Unit,
) {
    // 用 Modifier.toggleable 整合 click + focus + accessibility（Compose 為 row-level
    // toggle 設計）。TV 上 D-pad OK 觸發；Phone/Pad 觸控也 work。Switch onCheckedChange
    // 設 null 避免 Switch 自身消費 touch 事件導致 row click 失靈。
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(
                value = enabled,
                onValueChange = onToggle,
                role = Role.Switch,
            )
            .onFocusChanged { focused = it.isFocused }
            .background(
                if (focused) CinemaRed.copy(0.10f) else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = CinemaTextPrimary, fontSize = 13.sp,
                    fontWeight = FontWeight.Medium)
                if (isPrimary) {
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_primary_source), color = CinemaRed, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(CinemaRed.copy(0.15f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp))
                }
                // A broken endpoint answers HTTP 200 with an unparseable page, so without
                // this the only symptom is an empty catalogue. Healthy sources show just the
                // item count — a badge on every row would be noise.
                health?.let { h ->
                    Spacer(Modifier.width(8.dp))
                    when {
                        h.status.needsAttention -> {
                            val tint = if (h.status == com.gimy.tv.domain.model.EndpointHealthStatus.BROKEN)
                                CinemaRed else Color(0xFFF59E0B)
                            Text(h.status.label, color = tint, fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(tint.copy(0.15f), RoundedCornerShape(3.dp))
                                    .padding(horizontal = 5.dp, vertical = 1.dp))
                        }
                        h.status == com.gimy.tv.domain.model.EndpointHealthStatus.HEALTHY ->
                            Text(stringResource(R.string.settings_item_count, h.itemCount), color = CinemaTextMuted.copy(0.7f), fontSize = 10.sp)
                        else -> Unit
                    }
                }
            }
        }
        Switch(
            checked = enabled,
            onCheckedChange = null,  // row-level click handles toggle (TV-compatible)
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = CinemaRed,
                uncheckedThumbColor = CinemaTextMuted,
                uncheckedTrackColor = CinemaSurface,
                disabledCheckedThumbColor = Color.White,
                disabledCheckedTrackColor = CinemaRed,
                disabledUncheckedThumbColor = CinemaTextMuted,
                disabledUncheckedTrackColor = CinemaSurface,
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

@Composable
private fun statusLine(state: UpdateState, current: String): String = when (state) {
    UpdateState.Idle -> stringResource(R.string.settings_current_version, current)
    UpdateState.Checking -> stringResource(R.string.settings_update_checking)
    UpdateState.UpToDate -> stringResource(R.string.settings_update_latest)
    is UpdateState.Available -> stringResource(R.string.settings_update_found, state.info.latestVersion)
    is UpdateState.Downloading -> {
        val pct = if (state.total > 0) (state.downloaded * 100f / state.total).coerceIn(0f, 100f) else 0f
        stringResource(R.string.settings_downloading, "%.0f".format(pct))
    }
    is UpdateState.ReadyToInstall -> stringResource(R.string.settings_download_done)
    is UpdateState.Error -> stringResource(R.string.settings_error, state.message)
}

private fun currentVersionName(ctx: Context): String = runCatching {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
}.getOrDefault("?")
