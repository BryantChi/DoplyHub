package com.gimy.tv.ui.adult

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.displayName
import com.gimy.tv.ui.components.DoplyButton
import com.gimy.tv.ui.components.DoplyLoadingIndicator
import com.gimy.tv.ui.components.RefreshableContainer
import com.gimy.tv.ui.components.VodCard
import com.gimy.tv.ui.favorites.PageHeader
import com.gimy.tv.ui.theme.*

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AdultContentScreen(
    onVodClick: (SourceType, Long) -> Unit,
    onBack: () -> Unit,
    vm: AdultContentScreenViewModel = hiltViewModel(),
) {
    val dims = LocalDimensions.current
    val enabledSources by vm.enabledSources.collectAsState()
    val tabs = remember(enabledSources) { vm.adultTabs(enabledSources) }
    var selectedTab by remember { mutableStateOf<AdultTab?>(null) }

    LaunchedEffect(tabs) {
        if (tabs.isEmpty()) selectedTab = null
        else if (selectedTab == null || tabs.none { it.key == selectedTab?.key }) {
            selectedTab = tabs.firstOrNull()
        }
    }

    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CinemaBase, CinemaBlack)))
    ) {
        PageHeader("18+", onBack)

        if (tabs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("尚無可用 18+ 內容來源", color = CinemaTextPrimary,
                        fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("請至「設定 → 來源管理」啟用至少一個含成人分區的來源",
                        color = CinemaTextMuted, fontSize = 12.sp)
                }
            }
            return@Column
        }

        // Tab strip — one button per (source, typeId) pair.
        // gimy.tw contributes two tabs (露骨 / 劇情倫理); other sources one each.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = dims.screenHorizontalPadding, vertical = 12.dp),
        ) {
            items(tabs, key = { it.key }) { tab ->
                AdultSourceTab(
                    label = "${tab.sourceType.displayName} ${tab.label}",
                    selected = tab.key == selectedTab?.key,
                    onClick = { selectedTab = tab },
                )
            }
        }

        val current = selectedTab
        if (current != null) {
            val gridState = rememberLazyGridState()
            val isAtTop by remember {
                derivedStateOf {
                    gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
                }
            }
            val rowState by vm.rowFor(current).collectAsState()

            // Infinite scroll: when within 4 items of the bottom and we know there's more, fetch next page.
            // Re-keyed on `current.key` so switching tabs doesn't cross-trigger the previous tab's loadMore.
            LaunchedEffect(gridState, current.key) {
                snapshotFlow {
                    val info = gridState.layoutInfo
                    val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                    val total = info.totalItemsCount
                    lastVisible to total
                }.collect { (lastVisible, total) ->
                    if (total > 0 && lastVisible >= total - 4) {
                        vm.loadMore(current)
                    }
                }
            }

            RefreshableContainer(
                isRefreshing = rowState.loading && rowState.items.isNotEmpty(),
                onRefresh = { vm.refreshTab(current) },
                enabled = isAtTop,
            ) {
                when {
                    rowState.loading && rowState.items.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                DoplyLoadingIndicator(dims.loadingIndicatorSize)
                                Spacer(Modifier.height(12.dp))
                                Text("正在載入 ${current.sourceType.displayName} ${current.label} 內容…",
                                    color = CinemaTextMuted, fontSize = 12.sp)
                            }
                        }
                    }
                    rowState.items.isEmpty() && rowState.error != null -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("載入失敗", color = CinemaTextPrimary,
                                    fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(6.dp))
                                Text(rowState.error ?: "", color = CinemaTextMuted, fontSize = 12.sp)
                                Spacer(Modifier.height(16.dp))
                                DoplyButton(
                                    onClick = { vm.refreshTab(current) },
                                    containerColor = CinemaRed,
                                    shape = RoundedCornerShape(6.dp),
                                ) { Text("重試", color = Color.White, fontSize = 13.sp) }
                            }
                        }
                    }
                    rowState.items.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("此分類暫無內容",
                                color = CinemaTextMuted, fontSize = 13.sp)
                        }
                    }
                    else -> {
                        Column(Modifier.fillMaxSize()) {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(dims.gridMinCellWidth),
                                state = gridState,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(
                                    start = dims.screenHorizontalPadding,
                                    end = dims.screenHorizontalPadding,
                                    bottom = 8.dp,
                                ),
                                modifier = Modifier.weight(1f),
                            ) {
                                items(rowState.items, key = { "${it.sourceType}_${it.id}" }) { vod ->
                                    VodCard(vod, onClick = { onVodClick(vod.sourceType, vod.id) })
                                }
                            }
                            // Bottom: loading-more spinner OR "all shown" indicator
                            when {
                                rowState.loadingMore -> Box(
                                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center,
                                ) { DoplyLoadingIndicator(28.dp) }
                                !rowState.hasMore -> Box(
                                    Modifier.fillMaxWidth().padding(vertical = 14.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    // gimy.tw 39 (露骨) is a known dead-end: server returns the
                                    // same first page for every paginated URL pattern. We tell the
                                    // user the site itself is the bottleneck, not the App.
                                    val text = if (current.sourceType == SourceType.GIMY_TW && current.typeId == 39) {
                                        "已顯示全部 ${rowState.items.size} 部（此分類站方資料有限）"
                                    } else {
                                        "已顯示全部 ${rowState.items.size} 部"
                                    }
                                    Text(text, color = CinemaTextMuted.copy(0.6f), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AdultSourceTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val isTV = LocalIsTelevision.current
    var f by remember { mutableStateOf(false) }
    val container = when {
        selected -> CinemaRed
        f -> CinemaRed.copy(0.5f)
        else -> CinemaSurface
    }
    if (isTV) {
        Button(
            onClick = onClick,
            modifier = Modifier.onFocusChanged { f = it.isFocused },
            shape = ButtonDefaults.shape(shape = RoundedCornerShape(20.dp)),
            colors = ButtonDefaults.colors(containerColor = container, focusedContainerColor = CinemaRed),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp),
        ) {
            Text(label, color = Color.White, fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
        }
    } else {
        androidx.compose.material3.Button(
            onClick = onClick,
            shape = RoundedCornerShape(20.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = container, contentColor = Color.White),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp),
        ) {
            Text(label, color = Color.White, fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
        }
    }
}
