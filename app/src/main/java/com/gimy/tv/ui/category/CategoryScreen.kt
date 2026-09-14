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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gimy.tv.domain.model.displayName
import com.gimy.tv.domain.model.categoryMap
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.StandardCategory
import com.gimy.tv.ui.components.DoplyButton
import com.gimy.tv.ui.components.PinInputDialog
import com.gimy.tv.ui.settings.AdultContentViewModel
import com.gimy.tv.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.gimy.tv.R
import androidx.compose.ui.res.stringResource

data class CategoryItem(
    val sourceType: String,
    val typeId: Int,
    val name: String,
    val emoji: String,
)

/**
 * 分類瀏覽的排列順序與圖示。
 *
 * 只有 emoji 是這個畫面自己的資料；typeId 與名稱都從 [categoryMap] 與 [displayName] 推出來，
 * 不再手寫第二份——手寫的那份一旦與來源的編號表對不上，畫面就會「標題是甲、內容是乙」，
 * 而且不會有任何編譯錯誤。
 */
private val categoryOrder: List<Pair<StandardCategory, String>> = listOf(
    StandardCategory.SERIES to "📺",
    StandardCategory.MOVIE to "🎬",
    StandardCategory.ANIME to "🎌",
    StandardCategory.VARIETY to "🎤",
    StandardCategory.KOREAN to "🇰🇷",
    StandardCategory.CHINESE to "🇨🇳",
    StandardCategory.AMERICAN to "🇺🇸",
    StandardCategory.JAPANESE to "🇯🇵",
    StandardCategory.TAIWAN to "🇹🇼",
    StandardCategory.HK to "🇭🇰",
    StandardCategory.DOCUMENTARY to "🎥",
)

/** 分類瀏覽固定走 GIMYTV（首頁主力來源），其他來源從首頁的「更多來源」列進入。 */
private val categoryBrowseSource = SourceType.GIMYTV

internal val categories: List<CategoryItem> = categoryOrder.mapNotNull { (cat, emoji) ->
    categoryBrowseSource.categoryMap.typeIdFor(cat).takeIf { it > 0 }?.let { typeId ->
        CategoryItem(categoryBrowseSource.name, typeId, cat.displayName, emoji)
    }
}

@Composable
fun CategoryScreen(
    onCategoryClick: (sourceType: String, typeId: Int) -> Unit,
    onAdultZoneClick: () -> Unit = {},
    adultVm: AdultContentViewModel = hiltViewModel(),
) {
    val dims = LocalDimensions.current
    val adultEnabled by adultVm.enabled.collectAsStateWithLifecycle()
    val pinRequired by adultVm.pinRequired.collectAsStateWithLifecycle()
    val pinHash by adultVm.pinHash.collectAsStateWithLifecycle()
    val unlocked by adultVm.unlocked.collectAsStateWithLifecycle()
    val lockedUntilMs by adultVm.lockedUntilMs.collectAsStateWithLifecycle()

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
            stringResource(R.string.category_title),
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
        // 在 coroutine 裡取不到 Composable 的 context，先在組合階段讀出來
        val pinErrorText = stringResource(R.string.category_pin_error)
        PinInputDialog(
            title = stringResource(R.string.category_pin_prompt),
            subtitle = stringResource(R.string.category_pin_subtitle),
            failureMessage = pinErrorMsg,
            lockedRemainingSec = lockedRemaining,
            onPinComplete = { input ->
                scope.launch {
                    if (adultVm.verifyPin(input)) {
                        showPinDialog = false
                        pinErrorMsg = null
                        onAdultZoneClick()
                    } else {
                        pinErrorMsg = if (adultVm.isLocked()) null else pinErrorText
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
