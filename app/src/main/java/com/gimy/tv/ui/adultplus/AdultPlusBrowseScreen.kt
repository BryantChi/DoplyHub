package com.gimy.tv.ui.adultplus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.hilt.navigation.compose.hiltViewModel
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.ui.components.DoplyButton
import com.gimy.tv.ui.components.DoplyLoadingIndicator
import com.gimy.tv.ui.components.RefreshableContainer
import com.gimy.tv.ui.components.VodCard
import com.gimy.tv.ui.favorites.PageHeader
import com.gimy.tv.ui.theme.*

@Composable
fun AdultPlusBrowseScreen(
    onVodClick: (SourceType, Long) -> Unit,
    onBack: () -> Unit,
    vm: AdultPlusBrowseViewModel = hiltViewModel(),
) {
    val dims = LocalDimensions.current
    val state by vm.state.collectAsState()
    val gridState = rememberLazyGridState()
    val isAtTop by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
        }
    }

    // Infinite scroll: trigger loadMore when within 4 items of the bottom.
    LaunchedEffect(gridState, state.sourceType, state.pathKey) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = info.totalItemsCount
            lastVisible to total
        }.collect { (lastVisible, total) ->
            if (total > 0 && lastVisible >= total - 4) {
                vm.loadMore()
            }
        }
    }

    // Adult-plus listings (jable / xnxx) ship widescreen 16:9 thumbnails — use the
    // landscape VodCard variant so cropping doesn't kill the image. 5278 (BBS posts)
    // doesn't have proper covers anyway so landscape is harmless.
    val isLandscape = state.sourceType == SourceType.JABLE_TV ||
        state.sourceType == SourceType.XNXX

    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CinemaBase, CinemaBlack)))
    ) {
        PageHeader(state.title.ifBlank { "更多" }, onBack)

        RefreshableContainer(
            isRefreshing = state.isRefreshing,
            onRefresh = { vm.refresh() },
            enabled = isAtTop,
        ) {
            when {
                state.loading && state.items.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        DoplyLoadingIndicator(dims.loadingIndicatorSize)
                    }
                }
                state.error != null && state.items.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("載入失敗", color = CinemaTextPrimary,
                                fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text(state.error ?: "", color = CinemaTextMuted, fontSize = 12.sp)
                            Spacer(Modifier.height(16.dp))
                            DoplyButton(
                                onClick = { vm.refresh() },
                                containerColor = CinemaRed,
                                shape = RoundedCornerShape(6.dp),
                            ) { Text("重試", color = Color.White, fontSize = 13.sp) }
                        }
                    }
                }
                state.items.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("此分類暫無內容", color = CinemaTextMuted, fontSize = 13.sp)
                    }
                }
                else -> Column(Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        // Landscape cards are wider — bump the adaptive cell minimum so
                        // we pack 2-3 per row on phone instead of squeezing more in.
                        columns = GridCells.Adaptive(if (isLandscape) 240.dp else dims.gridMinCellWidth),
                        state = gridState,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(
                            start = dims.screenHorizontalPadding,
                            end = dims.screenHorizontalPadding,
                            top = 8.dp,
                            bottom = 8.dp,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        items(state.items, key = { "${it.sourceType}_${it.id}" }) { vod ->
                            VodCard(vod = vod, landscape = isLandscape, onClick = { onVodClick(vod.sourceType, vod.id) })
                        }
                    }
                    when {
                        state.loadingMore -> Box(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) { DoplyLoadingIndicator(28.dp) }
                        !state.hasMore && state.items.isNotEmpty() -> Box(
                            Modifier.fillMaxWidth().padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("已顯示全部 ${state.items.size} 部",
                                color = CinemaTextMuted.copy(0.6f), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
