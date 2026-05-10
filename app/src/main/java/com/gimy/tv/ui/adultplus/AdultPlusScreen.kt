package com.gimy.tv.ui.adultplus

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.ui.components.DoplyLoadingIndicator
import com.gimy.tv.ui.components.RefreshableContainer
import com.gimy.tv.ui.components.VodCard
import com.gimy.tv.ui.favorites.PageHeader
import com.gimy.tv.ui.components.FocusableChip
import com.gimy.tv.ui.theme.*

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AdultPlusScreen(
    onVodClick: (SourceType, Long) -> Unit,
    onBack: () -> Unit,
    /** Navigate to the per-row「查看更多」full-grid page. Receives (sourceType, pathKey, title). */
    onMoreClick: (SourceType, String, String) -> Unit = { _, _, _ -> },
    /** Navigate to the standalone「全部分類」index screen. */
    onCategoriesClick: () -> Unit = {},
    vm: AdultPlusViewModel = hiltViewModel(),
) {
    val dims = LocalDimensions.current
    val listState = rememberLazyListState()
    val isAtTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CinemaBase, CinemaBlack)))
    ) {
        PageHeader("進階", onBack)

        // Entry to the「全部分類」index. Sits above the refresh container so it's
        // always reachable; the refresh gesture only consumes pulls from the
        // scrollable area below.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = dims.screenHorizontalPadding, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            FocusableChip("📂 全部分類") { onCategoriesClick() }
        }

        val isRefreshing by vm.isRefreshing.collectAsState()
        RefreshableContainer(
            isRefreshing = isRefreshing,
            onRefresh = { vm.refreshAll() },
            enabled = isAtTop,
        ) {
            val history by vm.adultHistory.collectAsState()
            val favorites by vm.adultFavorites.collectAsState()

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = dims.screenHorizontalPadding),
            ) {
                if (history.isNotEmpty()) {
                    item(key = "adult_history_row") {
                        AdultStaticRow(
                            title = "📜 我的觀看歷史",
                            items = history,
                            onItemClick = { vod -> onVodClick(vod.sourceType, vod.id) },
                        )
                    }
                }
                if (favorites.isNotEmpty()) {
                    item(key = "adult_favorites_row") {
                        AdultStaticRow(
                            title = "⭐ 我的收藏",
                            items = favorites,
                            onItemClick = { vod -> onVodClick(vod.sourceType, vod.id) },
                        )
                    }
                }
                items(vm.rows, key = { "${it.sourceType.name}_${it.key}" }) { row ->
                    AdultPlusRowSection(
                        row = row,
                        vm = vm,
                        onItemClick = { vod -> onVodClick(vod.sourceType, vod.id) },
                        onMoreClick = { onMoreClick(row.sourceType, row.key, row.title) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AdultPlusRowSection(
    row: AdultPlusRow,
    vm: AdultPlusViewModel,
    onItemClick: (com.gimy.tv.domain.model.Vod) -> Unit,
    onMoreClick: () -> Unit,
) {
    val dims = LocalDimensions.current
    val state by vm.rowState(row).collectAsState()

    Column(Modifier.padding(top = 20.dp)) {
        // Section header (matches HomeScreen style)
        Row(
            Modifier.padding(start = dims.screenHorizontalPadding, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(3.dp).height(16.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(CinemaRed),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                row.title,
                fontSize = 16.sp, fontWeight = FontWeight.Bold,
                color = CinemaTextPrimary, letterSpacing = 0.3.sp,
            )
        }

        when {
            state.loading && state.items.isEmpty() -> {
                Box(
                    Modifier.fillMaxWidth().height(dims.cardHeight),
                    contentAlignment = Alignment.Center,
                ) { DoplyLoadingIndicator(28.dp) }
            }
            state.error != null && state.items.isEmpty() -> {
                Row(
                    Modifier.padding(horizontal = dims.screenHorizontalPadding, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("載入失敗：${state.error}", color = CinemaTextMuted, fontSize = 12.sp)
                    Spacer(Modifier.width(12.dp))
                    RetryChip { vm.refreshRow(row) }
                }
            }
            state.items.isEmpty() -> {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("此分類暫無內容", color = CinemaTextMuted.copy(0.6f), fontSize = 12.sp)
                }
            }
            else -> {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = dims.screenHorizontalPadding),
                    horizontalArrangement = Arrangement.spacedBy(dims.cardSpacing),
                ) {
                    items(state.items, key = { "${it.sourceType}_${it.id}" }) { vod ->
                        VodCard(vod = vod, landscape = true, onClick = { onItemClick(vod) })
                    }
                    // "→ 查看更多" trailing card — like HomeScreen ContentRow's MoreCard,
                    // tapping navigates to a dedicated full-grid page (AdultPlusBrowseScreen)
                    // with infinite scroll. Always shown if the source claims more pages.
                    if (state.hasMore || state.items.size >= 20) {
                        item(key = "load_more_${row.key}") {
                            LoadMoreCard(
                                loading = false,
                                onClick = onMoreClick,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Lightweight static row used for "我的歷史 / 我的收藏" — no ViewModel state, just
 *  a fixed Vod list. Same visual as AdultPlusRowSection but without loading/error logic. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AdultStaticRow(
    title: String,
    items: List<com.gimy.tv.domain.model.Vod>,
    onItemClick: (com.gimy.tv.domain.model.Vod) -> Unit,
) {
    val dims = LocalDimensions.current
    Column(Modifier.padding(top = 20.dp)) {
        Row(
            Modifier.padding(start = dims.screenHorizontalPadding, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(3.dp).height(16.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(CinemaRed),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                fontSize = 16.sp, fontWeight = FontWeight.Bold,
                color = CinemaTextPrimary, letterSpacing = 0.3.sp,
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = dims.screenHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(dims.cardSpacing),
        ) {
            items(items, key = { "${it.sourceType}_${it.id}" }) { vod ->
                VodCard(vod = vod, landscape = true, onClick = { onItemClick(vod) })
            }
        }
    }
}

/** Trailing "→ 更多" card matching the 16:9 cover-card aspect of AdultPlus rows.
 *  Shows a spinner overlay while a page is loading; otherwise the arrow + "更多" text. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LoadMoreCard(loading: Boolean, onClick: () -> Unit) {
    val dims = LocalDimensions.current
    val isTV = LocalIsTelevision.current
    var f by remember { mutableStateOf(false) }
    // Match landscape cards' geometry so the row's vertical alignment stays clean
    val w = (dims.cardWidth.value * 1.55f).dp
    val h = (w.value * 9f / 16f).dp + 24.dp

    @Composable fun cardBody() {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (loading) {
                DoplyLoadingIndicator(28.dp)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("▶", fontSize = 24.sp, color = if (f) CinemaRed else CinemaTextMuted)
                    Spacer(Modifier.height(6.dp))
                    Text("更多", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = if (f) CinemaRed else CinemaTextPrimary)
                }
            }
        }
    }

    if (isTV) {
        androidx.tv.material3.Card(
            onClick = onClick,
            modifier = Modifier.width(w).height(h).onFocusChanged { f = it.isFocused },
            shape = androidx.tv.material3.CardDefaults.shape(shape = RoundedCornerShape(8.dp)),
            border = androidx.tv.material3.CardDefaults.border(
                focusedBorder = androidx.tv.material3.Border(BorderStroke(2.dp, CinemaRed), 8.dp)),
            scale = androidx.tv.material3.CardDefaults.scale(focusedScale = 1.05f),
            colors = androidx.tv.material3.CardDefaults.colors(containerColor = CinemaSurface),
        ) { cardBody() }
    } else {
        androidx.compose.material3.Card(
            onClick = onClick,
            modifier = Modifier.width(w).height(h),
            shape = RoundedCornerShape(8.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = CinemaSurface),
        ) { cardBody() }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RetryChip(onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val isTV = LocalIsTelevision.current
    if (isTV) {
        Button(
            onClick = onClick,
            modifier = Modifier.onFocusChanged { f = it.isFocused },
            shape = ButtonDefaults.shape(shape = RoundedCornerShape(6.dp)),
            colors = ButtonDefaults.colors(containerColor = if (f) CinemaRed else CinemaSurface, focusedContainerColor = CinemaRed),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp),
        ) { Text("重試", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
    } else {
        androidx.compose.material3.Button(
            onClick = onClick,
            shape = RoundedCornerShape(6.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = CinemaRed, contentColor = Color.White),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp),
        ) { Text("重試", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
    }
}
