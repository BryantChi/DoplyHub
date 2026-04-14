package com.gimy.tv.ui.browse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.gimy.tv.ui.components.GimyLoadingIndicator
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
    val uiState by vm.uiState.collectAsState()
    val gridState = rememberLazyGridState()

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
            Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            var bf by remember { mutableStateOf(false) }
            Button(
                onClick = onBack,
                modifier = Modifier.onFocusChanged { bf = it.isFocused },
                shape = ButtonDefaults.shape(shape = RoundedCornerShape(6.dp)),
                colors = ButtonDefaults.colors(containerColor = CinemaSurface, focusedContainerColor = CinemaRed),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp)
            ) { Text("返回", color = Color.White, fontSize = 13.sp) }

            Spacer(Modifier.width(16.dp))
            Text(uiState.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = CinemaTextPrimary)

            if (uiState.items.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                Text("第${uiState.currentPage}頁", fontSize = 13.sp, color = CinemaTextMuted)
            }
        }

        when {
            uiState.isLoading && uiState.items.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        GimyLoadingIndicator(48.dp)
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
                        Button(onClick = { vm.loadPage(1) }, colors = ButtonDefaults.colors(containerColor = CinemaRed)) {
                            Text("重試", color = Color.White)
                        }
                    }
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(166.dp),
                    state = gridState,
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
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
                            Box(Modifier.width(154.dp).height(248.dp), contentAlignment = Alignment.Center) {
                                if (uiState.isLoadingMore) {
                                    GimyLoadingIndicator(28.dp)
                                } else {
                                    Card(
                                        onClick = { vm.loadMore() },
                                        shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp)),
                                        border = CardDefaults.border(focusedBorder = Border(BorderStroke(2.dp, CinemaRed), 8.dp)),
                                        scale = CardDefaults.scale(focusedScale = 1f),
                                        colors = CardDefaults.colors(containerColor = CinemaSurface),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("↓", fontSize = 24.sp, color = CinemaRed)
                                                Spacer(Modifier.height(4.dp))
                                                Text("載入更多", fontSize = 13.sp, color = CinemaTextPrimary, fontWeight = FontWeight.Bold)
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
}
