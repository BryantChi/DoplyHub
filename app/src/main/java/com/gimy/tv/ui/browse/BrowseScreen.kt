package com.gimy.tv.ui.browse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.gimy.tv.ui.components.DoplyButton
import com.gimy.tv.ui.components.DoplyLoadingIndicator
import com.gimy.tv.ui.components.RefreshIconButton
import com.gimy.tv.ui.components.RefreshLoadingBar
import com.gimy.tv.ui.components.RefreshableContainer
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
import com.gimy.tv.ui.components.VodCard
import com.gimy.tv.ui.theme.*

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BrowseScreen(
    onVodClick: (SourceType, Long) -> Unit,
    onBack: () -> Unit,
    vm: BrowseViewModel = hiltViewModel()
) {
    val dims = LocalDimensions.current
    val uiState by vm.uiState.collectAsState()
    val gridState = rememberLazyGridState()
    val browseIsAtTop by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
        }
    }

    // Auto load more: trigger when the last visible item is within 4 items of the end
    LaunchedEffect(gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index) {
        val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        val total = gridState.layoutInfo.totalItemsCount
        if (total > 0 && lastVisible >= total - 4) {
            vm.loadMore()
        }
    }

    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CinemaBase, CinemaBlack)))) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(horizontal = dims.screenHorizontalPadding, vertical = dims.screenVerticalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isTV = LocalIsTelevision.current
            var bf by remember { mutableStateOf(false) }
            if (isTV) {
                Button(
                    onClick = onBack,
                    modifier = Modifier.onFocusChanged { bf = it.isFocused },
                    shape = ButtonDefaults.shape(shape = RoundedCornerShape(6.dp)),
                    colors = ButtonDefaults.colors(containerColor = CinemaSurface, focusedContainerColor = CinemaRed),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp)
                ) { Text("返回", color = Color.White, fontSize = 13.sp) }
            } else {
                androidx.compose.material3.Button(
                    onClick = onBack,
                    shape = RoundedCornerShape(6.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = CinemaSurface, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp)
                ) { Text("返回", color = Color.White, fontSize = 13.sp) }
            }

            Spacer(Modifier.width(16.dp))
            Text(uiState.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = CinemaTextPrimary)

            if (uiState.items.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                Text("第${uiState.currentPage}頁", fontSize = 13.sp, color = CinemaTextMuted)
            }

            Spacer(Modifier.weight(1f))
            RefreshIconButton(isRefreshing = uiState.isRefreshing, onClick = { vm.refresh() })
        }
        RefreshLoadingBar(uiState.isRefreshing)

        when {
            uiState.isLoading && uiState.items.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        DoplyLoadingIndicator(dims.loadingIndicatorSize)
                        Spacer(Modifier.height(12.dp))
                        Text("載入${uiState.title}…", color = CinemaTextMuted, fontSize = 14.sp)
                    }
                }
            }
            uiState.error != null && uiState.items.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("載入失敗", color = CinemaTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(uiState.error!!, color = CinemaTextMuted, fontSize = 13.sp)
                        Spacer(Modifier.height(16.dp))
                        DoplyButton(onClick = { vm.loadPage(1) }, containerColor = CinemaRed) {
                            Text("重試", color = Color.White)
                        }
                    }
                }
            }
            else -> {
                RefreshableContainer(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = { vm.refresh() },
                    enabled = browseIsAtTop,
                ) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(dims.gridMinCellWidth),
                    state = gridState,
                    contentPadding = PaddingValues(horizontal = dims.screenHorizontalPadding, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.items, key = { "${it.sourceType}_${it.id}" }) { vod ->
                        VodCard(vod, onClick = { onVodClick(vod.sourceType, vod.id) })
                    }

                    // Bottom: loading indicator or "load more" button
                    if (uiState.hasMore) {
                        item {
                            Box(Modifier.width(dims.cardWidth).height(dims.cardHeight), contentAlignment = Alignment.Center) {
                                if (uiState.isLoadingMore) {
                                    DoplyLoadingIndicator(28.dp)
                                } else {
                                    val loadMoreContent: @Composable () -> Unit = {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("↓", fontSize = 24.sp, color = CinemaRed)
                                                Spacer(Modifier.height(4.dp))
                                                Text("載入更多", fontSize = 13.sp, color = CinemaTextPrimary, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                    if (LocalIsTelevision.current) {
                                        Card(
                                            onClick = { vm.loadMore() },
                                            shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp)),
                                            border = CardDefaults.border(focusedBorder = Border(BorderStroke(2.dp, CinemaRed), 8.dp)),
                                            scale = CardDefaults.scale(focusedScale = 1f),
                                            colors = CardDefaults.colors(containerColor = CinemaSurface),
                                            modifier = Modifier.fillMaxSize()
                                        ) { loadMoreContent() }
                                    } else {
                                        androidx.compose.material3.Card(
                                            onClick = { vm.loadMore() },
                                            shape = RoundedCornerShape(8.dp),
                                            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = CinemaSurface),
                                            modifier = Modifier.fillMaxSize()
                                        ) { loadMoreContent() }
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }
}
