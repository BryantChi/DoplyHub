package com.gimy.tv.ui.category

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gimy.tv.ui.components.DoplyButton
import com.gimy.tv.ui.components.PinInputDialog
import com.gimy.tv.ui.settings.AdultContentViewModel
import com.gimy.tv.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class CategoryItem(
    val sourceType: String,
    val typeId: Int,
    val name: String,
    val emoji: String,
)

private val categories = listOf(
    CategoryItem("GIMYTV", 2, "電視劇", "📺"),
    CategoryItem("GIMYTV", 1, "電影", "🎬"),
    CategoryItem("GIMYTV", 4, "動漫", "🎌"),
    CategoryItem("GIMYTV", 29, "綜藝", "🎤"),
    CategoryItem("GIMYTV", 20, "韓劇", "🇰🇷"),
    CategoryItem("GIMYTV", 13, "陸劇", "🇨🇳"),
    CategoryItem("GIMYTV", 16, "美劇", "🇺🇸"),
    CategoryItem("GIMYTV", 21, "日劇", "🇯🇵"),
    CategoryItem("GIMYTV", 14, "台劇", "🇹🇼"),
    CategoryItem("GIMYTV", 15, "港劇", "🇭🇰"),
    CategoryItem("GIMYTV", 3, "紀錄片", "🎥"),
)

@Composable
fun CategoryScreen(
    onCategoryClick: (sourceType: String, typeId: Int) -> Unit,
    onAdultZoneClick: () -> Unit = {},
    adultVm: AdultContentViewModel = hiltViewModel(),
) {
    val dims = LocalDimensions.current
    val adultEnabled by adultVm.enabled.collectAsState()
    val pinRequired by adultVm.pinRequired.collectAsState()
    val pinHash by adultVm.pinHash.collectAsState()
    val unlocked by adultVm.unlocked.collectAsState()
    val lockedUntilMs by adultVm.lockedUntilMs.collectAsState()

    var showPinDialog by remember { mutableStateOf(false) }
    var pinErrorMsg by remember { mutableStateOf<String?>(null) }
    var lockedRemaining by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(lockedUntilMs, showPinDialog) {
        while (showPinDialog) {
            val remaining = adultVm.remainingLockSeconds()
            lockedRemaining = remaining
            if (remaining <= 0L) break
            delay(1000)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(CinemaBlack)
            .padding(horizontal = dims.screenHorizontalPadding, vertical = dims.screenVerticalPadding)
    ) {
        Text(
            "分類瀏覽",
            color = CinemaTextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(16.dp))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(dims.gridMinCellWidth),
            horizontalArrangement = Arrangement.spacedBy(dims.cardSpacing),
            verticalArrangement = Arrangement.spacedBy(dims.cardSpacing),
            modifier = Modifier.fillMaxSize()
        ) {
            items(categories, key = { "${it.sourceType}_${it.typeId}" }) { cat ->
                DoplyButton(
                    onClick = { onCategoryClick(cat.sourceType, cat.typeId) },
                    shape = RoundedCornerShape(12.dp),
                    containerColor = CinemaElevated,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(cat.emoji, fontSize = 20.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            cat.name,
                            color = CinemaTextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            if (adultEnabled) {
                item(key = "category_adult") {
                    DoplyButton(
                        onClick = {
                            // Gate: skip dialog if PIN not required, no PIN set, or already unlocked this session
                            if (!pinRequired || pinHash == null || unlocked) {
                                adultVm.markUnlocked()
                                onAdultZoneClick()
                            } else {
                                pinErrorMsg = null
                                showPinDialog = true
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        containerColor = CinemaRed.copy(0.18f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("🔞", fontSize = 20.sp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "18+",
                                color = CinemaRed,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPinDialog) {
        PinInputDialog(
            title = "請輸入 PIN",
            subtitle = "進入 18+ 區",
            failureMessage = pinErrorMsg,
            lockedRemainingSec = lockedRemaining,
            onPinComplete = { input ->
                scope.launch {
                    if (adultVm.verifyPin(input)) {
                        showPinDialog = false
                        pinErrorMsg = null
                        onAdultZoneClick()
                    } else {
                        pinErrorMsg = if (adultVm.isLocked()) null else "PIN 錯誤"
                    }
                }
            },
            onDismiss = {
                showPinDialog = false
                pinErrorMsg = null
            },
        )
    }
}
