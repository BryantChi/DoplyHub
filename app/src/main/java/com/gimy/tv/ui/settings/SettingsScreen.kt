package com.gimy.tv.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.gimy.tv.data.update.UpdateState
import com.gimy.tv.ui.components.DoplyButton
import com.gimy.tv.ui.favorites.PageHeader
import com.gimy.tv.ui.theme.*
import com.gimy.tv.ui.update.UpdateViewModel

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: UpdateViewModel = hiltViewModel(),
) {
    val dims = LocalDimensions.current
    val ctx = LocalContext.current
    val state by vm.state.collectAsState()
    val versionName = remember { currentVersionName(ctx) }

    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CinemaBase, CinemaBlack)))
    ) {
        PageHeader("設定", onBack)

        Column(
            Modifier.fillMaxSize().padding(horizontal = dims.screenHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingSection(title = "關於") {
                InfoRow("應用名稱", "Doply Hub")
                InfoRow("版本", "v$versionName")
            }

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
