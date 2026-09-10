package com.gimy.tv.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.gimy.tv.ui.components.DoplyLoadingIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.StandardCategory
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.domain.model.categoryMap
import com.gimy.tv.domain.model.displayName
import com.gimy.tv.ui.components.DoplyButton
import com.gimy.tv.ui.components.ExitConfirmHandler
import com.gimy.tv.ui.components.RefreshIconButton
import com.gimy.tv.ui.components.RefreshLoadingBar
import com.gimy.tv.ui.components.RefreshableContainer
import com.gimy.tv.ui.components.VodCard
import com.gimy.tv.ui.theme.*
import com.gimy.tv.ui.theme.LocalDimensions
import com.gimy.tv.ui.theme.LocalIsTelevision
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    onVodClick: (SourceType, Long) -> Unit,
    onSearchClick: () -> Unit,
    onBrowseClick: (SourceType, Int) -> Unit,
    onCategoriesClick: () -> Unit,
    onFavoritesClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    isPhone: Boolean,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val enabledSources by viewModel.enabledSources.collectAsState()
    val dims = LocalDimensions.current
    val isTV = LocalIsTelevision.current
    val listState = rememberLazyListState()
    // Pull-to-refresh only enabled when the list is at the very top — avoids accidental
    // refreshes triggered while scrolling within long content.
    val isAtTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    ExitConfirmHandler(isPhone = isPhone)

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(CinemaBase, CinemaBlack, CinemaBase)))
    ) {
        when {
            uiState.isLoading -> LoadingOverlay()
            uiState.error != null -> ErrorOverlay(uiState.error ?: "") { viewModel.retry() }
            else -> {
                RefreshableContainer(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    enabled = isAtTop,
                ) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = dims.screenHorizontalPadding)) {
                    // ── Top bar ──
                    if (isTV) {
                        item { TopBar(onSearchClick, onCategoriesClick, onFavoritesClick, onHistoryClick, onSettingsClick, uiState.isRefreshing) { viewModel.refresh() } }
                    } else {
                        item { LogoBrand() }
                    }
                    item { RefreshLoadingBar(uiState.isRefreshing) }

                    // ── Hero banner ──
                    val heroItems = uiState.rows.firstOrNull()?.items?.take(6) ?: emptyList()
                    if (heroItems.isNotEmpty()) {
                        item { HeroBanner(heroItems) { onVodClick(it.sourceType, it.id) } }
                    }

                    // ── Continue watching ──
                    if (uiState.continueWatching.isNotEmpty()) {
                        item {
                            ContinueWatchingRow(
                                entries = uiState.continueWatching,
                                onItemClick = { onVodClick(it.sourceType, it.id) },
                                onMoreClick = { onHistoryClick() }
                            )
                        }
                    }

                    // ── Content rows ──
                    items(uiState.rows, key = { "${it.sourceType}_${it.typeId}" }) { row ->
                        ContentRow(row.title, row.typeId, row.sourceType, row.items,
                            onItemClick = { onVodClick(it.sourceType, it.id) },
                            onMoreClick = { onBrowseClick(row.sourceType, row.typeId) }
                        )
                    }

                    // ── More Sources (lazy-loaded; filtered by user-enabled set) ──
                    val activeMoreSources = MORE_SOURCES.filter { it.first in enabledSources }
                    if (activeMoreSources.isNotEmpty()) {
                        item(key = "more_sources_header") { MoreSourcesHeader() }
                    }
                    items(activeMoreSources, key = { "more_${it.first.name}_${it.second.name}" }) { (source, cat) ->
                        val typeId = source.categoryMap.typeIdFor(cat)
                        if (typeId > 0) {
                            MoreSourceRow(
                                sourceType = source,
                                typeId = typeId,
                                title = "${source.displayName} · ${categoryDisplayName(cat)}",
                                onItemClick = { onVodClick(it.sourceType, it.id) },
                                onMoreClick = { onBrowseClick(source, typeId) },
                                viewModel = viewModel,
                            )
                        }
                    }
                }
                }
            }
        }
    }
}

// ═══════════════════════════════════════
// Top navigation
// ═══════════════════════════════════════

@Composable
private fun LogoBrand() {
    val dims = LocalDimensions.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = dims.screenHorizontalPadding, vertical = 12.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Text("D", fontSize = 28.sp, fontWeight = FontWeight.Black, color = CinemaRed)
        Text("oply", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = CinemaTextPrimary, modifier = Modifier.offset(x = (-2).dp))
        Spacer(Modifier.width(4.dp))
        Text("Hub", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CinemaTextMuted,
            modifier = Modifier.offset(y = (-2).dp).background(CinemaRed.copy(0.15f), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TopBar(
    onSearch: () -> Unit,
    onCategories: () -> Unit,
    onFav: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val dims = LocalDimensions.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = dims.screenHorizontalPadding, vertical = dims.screenVerticalPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Brand
        Row(verticalAlignment = Alignment.Bottom) {
            Text("D", fontSize = 30.sp, fontWeight = FontWeight.Black, color = CinemaRed)
            Text("oply", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = CinemaTextPrimary, modifier = Modifier.offset(x = (-2).dp))
            Spacer(Modifier.width(4.dp))
            Text("Hub", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = CinemaTextMuted,
                modifier = Modifier.offset(y = (-2).dp).background(CinemaRed.copy(0.15f), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NavChip("搜尋", onSearch)
            NavChip("分類", onCategories)
            NavChip("收藏", onFav)
            NavChip("歷史", onHistory)
            NavChip("設定", onSettings)
            RefreshIconButton(isRefreshing = isRefreshing, onClick = onRefresh)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NavChip(label: String, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { f = it.isFocused },
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(6.dp)),
        colors = ButtonDefaults.colors(
            containerColor = if (f) CinemaRed else CinemaSurface.copy(0.6f),
            focusedContainerColor = CinemaRed
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 7.dp)
    ) { Text(label, fontSize = 13.sp, color = if (f) Color.White else CinemaTextMuted, fontWeight = FontWeight.Medium) }
}

// ═══════════════════════════════════════
// Hero banner with auto-rotate
// ═══════════════════════════════════════

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HeroBanner(items: List<Vod>, onItemClick: (Vod) -> Unit) {
    val dims = LocalDimensions.current
    val isTV = LocalIsTelevision.current
    var idx by remember { mutableIntStateOf(0) }

    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    // Auto-rotate (guard against empty list after recomposition)
    LaunchedEffect(idx, items.size) {
        if (items.isEmpty()) return@LaunchedEffect
        delay(7000)
        idx = (idx + 1) % items.size
    }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = dims.screenHorizontalPadding, vertical = 6.dp)
            .height(dims.heroBannerHeight)
            .clip(RoundedCornerShape(12.dp))
            .bringIntoViewRequester(bringIntoViewRequester)
            .then(
                if (!isTV && items.size > 1) {
                    Modifier.pointerInput(items.size) {
                        var totalDrag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { totalDrag = 0f },
                            onDragEnd = {
                                if (totalDrag > 80f) {
                                    idx = if (idx > 0) idx - 1 else items.size - 1
                                } else if (totalDrag < -80f) {
                                    idx = (idx + 1) % items.size
                                }
                            },
                            onHorizontalDrag = { _, dragAmount -> totalDrag += dragAmount }
                        )
                    }
                } else Modifier
            )
    ) {
        // ── Dark base background ──
        Box(Modifier.fillMaxSize().background(CinemaBase))

        // ── Crossfade: image + text info only (button stays outside to keep focus) ──
        Crossfade(targetState = idx, animationSpec = tween(800), label = "banner") { currentIdx ->
            val vod = items.getOrNull(currentIdx % items.size) ?: return@Crossfade
            Box(Modifier.fillMaxSize()) {
                // Cover image on right side, crop from 20% top
                Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.62f)) {
                    AsyncImage(
                        model = vod.coverUrl, contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = BiasAlignment(0f, -0.6f),
                        modifier = Modifier.fillMaxSize()
                    )
                    // Fade left edge into dark background
                    Box(Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(
                            0.0f to CinemaBase,
                            0.15f to CinemaBase.copy(0.85f),
                            0.3f to CinemaBase.copy(0.6f),
                            0.45f to CinemaBase.copy(0.35f),
                            0.6f to CinemaBase.copy(0.1f),
                            0.75f to Color.Transparent
                        )
                    ))
                    // Bottom fade for dots readability
                    Box(Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0f to Color.Transparent, 0.85f to CinemaBase.copy(0.7f))
                    ))
                }

                // Text info on dark left side
                Column(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(
                            start = dims.screenHorizontalPadding,
                            top = if (isTV) 32.dp else 16.dp,
                            bottom = if (isTV) 80.dp else 32.dp
                        )
                        .fillMaxHeight()
                        .fillMaxWidth(dims.heroBannerTextWidthFraction),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("${currentIdx + 1} / ${items.size}", color = CinemaRed, fontSize = dims.heroBannerMetaSize, fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(CinemaRed.copy(0.15f), RoundedCornerShape(3.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
                    Spacer(Modifier.height(if (isTV) 8.dp else 4.dp))
                    Text(vod.title, color = Color.White,
                        fontSize = dims.heroBannerTitleSize,
                        fontWeight = FontWeight.Black,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        lineHeight = dims.heroBannerTitleSize * 1.25f)
                    Spacer(Modifier.height(if (isTV) 6.dp else 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (vod.year > 0) {
                            Text("${vod.year}", color = CinemaTextMuted, fontSize = dims.heroBannerMetaSize)
                        }
                        if (vod.category.isNotBlank()) {
                            Text(vod.category, color = CinemaTextMuted, fontSize = dims.heroBannerMetaSize)
                        }
                        // Same defensive fallback as VodCard — see comment there.
                        @Suppress("DEPRECATION")
                        val prettyHeroStatus = remember(vod.siteStatus, vod.status) {
                            val s = vod.siteStatus
                            if (s !is com.gimy.tv.domain.model.EpisodeStatus.Empty) s.display
                            else com.gimy.tv.domain.util.parseEpisodeStatus(vod.status).display
                        }
                        if (prettyHeroStatus.isNotBlank()) {
                            Text(prettyHeroStatus, color = CinemaGold, fontSize = dims.heroBannerMetaSize, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    // Phone: button inside text column to avoid overlap
                    if (!isTV) {
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.Button(
                            onClick = { items.getOrNull(currentIdx % items.size)?.let(onItemClick) },
                            shape = RoundedCornerShape(6.dp),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = CinemaRed, contentColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                        ) {
                            Text("▶  觀看詳情", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // ── Button: outside Crossfade so focus persists across transitions (TV only) ──
        if (isTV) {
            var btnFocused by remember { mutableStateOf(false) }
            Button(
                onClick = { items.getOrNull(idx)?.let(onItemClick) },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = dims.screenHorizontalPadding * 2, bottom = 44.dp)
                    .onFocusChanged {
                        btnFocused = it.isFocused
                        if (it.isFocused) coroutineScope.launch { bringIntoViewRequester.bringIntoView() }
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                            when (event.nativeKeyEvent.keyCode) {
                                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                                    idx = if (idx > 0) idx - 1 else items.size - 1; true
                                }
                                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    idx = (idx + 1) % items.size; true
                                }
                                else -> false
                            }
                        } else false
                    },
                shape = ButtonDefaults.shape(shape = RoundedCornerShape(6.dp)),
                colors = ButtonDefaults.colors(containerColor = CinemaRed, focusedContainerColor = Color.White),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 11.dp)
            ) {
                Text(
                    if (btnFocused) "◀  觀看詳情  ▶" else "▶  觀看詳情",
                    color = if (btnFocused) CinemaBlack else Color.White,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp
                )
            }
        }

        // Progress dots (outside Crossfade so they don't fade)
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp), Arrangement.spacedBy(5.dp)) {
            items.forEachIndexed { i, _ ->
                Box(Modifier.size(if (i == idx) 24.dp else 5.dp, 4.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (i == idx) CinemaRed else Color.White.copy(0.25f)))
            }
        }
    }
}

// ═══════════════════════════════════════
// Continue watching row
// ═══════════════════════════════════════

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ContinueWatchingRow(
    entries: List<com.gimy.tv.domain.repository.WatchHistoryEntry>,
    onItemClick: (Vod) -> Unit,
    onMoreClick: () -> Unit,
) {
    val dims = LocalDimensions.current

    Column(Modifier.padding(top = 20.dp)) {
        Row(Modifier.padding(start = dims.screenHorizontalPadding, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(CinemaRed))
            Spacer(Modifier.width(10.dp))
            Text("繼續觀看", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = CinemaTextPrimary, letterSpacing = 0.3.sp)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = dims.screenHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(dims.cardSpacing)
        ) {
            items(entries, key = { "${it.sourceType}_${it.vodId}" }) { entry ->
                // Build Vod with empty status — the show's site release stage is NOT
                // stored in WatchHistoryEntry, so we leave siteStatus = Empty and status = "".
                // The red top-right badge will be blank (correct: we don't know the current
                // site status from history alone).
                val vod = remember(entry) {
                    Vod(entry.vodId, entry.sourceType, entry.title, entry.coverUrl, "", 0, "")
                }
                Box {
                    VodCard(vod = vod, onClick = { onItemClick(vod) })
                    // Watch-progress hint: bottom-end, dark background, NOT the red status badge.
                    // Visually distinct from the top-right red badge so users don't mistake
                    // their own progress ("看到第N集") for the show's release stage.
                    if (entry.episodeNum > 0) {
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                                .background(Color(0xCC000000), RoundedCornerShape(3.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "看到第${entry.episodeNum}集",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
            // "More" card at the end of the row
            item(key = "more_continue") {
                MoreCard(onClick = onMoreClick)
            }
        }
    }
}

// ═══════════════════════════════════════
// Content row
// ═══════════════════════════════════════

private val MovieffmBlue = Color(0xFF3B82F6)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ContentRow(title: String, typeId: Int, rowSourceType: SourceType, items: List<Vod>, onItemClick: (Vod) -> Unit, onMoreClick: () -> Unit) {
    val dims = LocalDimensions.current
    val isMovieffm = rowSourceType == SourceType.MOVIEFFM
    val accentColor = if (isMovieffm) MovieffmBlue else CinemaRed

    Column(Modifier.padding(top = 20.dp)) {
        Row(Modifier.padding(start = dims.screenHorizontalPadding, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(accentColor))
            Spacer(Modifier.width(10.dp))
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = CinemaTextPrimary, letterSpacing = 0.3.sp)
            if (isMovieffm) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.background(MovieffmBlue.copy(0.85f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("FFM", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = dims.screenHorizontalPadding), horizontalArrangement = Arrangement.spacedBy(dims.cardSpacing)) {
            items(items, key = { "${it.sourceType}_${it.id}" }) { vod ->
                VodCard(vod = vod, onClick = { onItemClick(vod) })
            }
            // "More" card at the end of the row
            item(key = "more_$typeId") {
                MoreCard(onClick = onMoreClick)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MoreCard(onClick: () -> Unit) {
    val dims = LocalDimensions.current
    val isTV = LocalIsTelevision.current
    var f by remember { mutableStateOf(false) }

    @Composable fun MoreCardContent() {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("▶", fontSize = 28.sp, color = if (f) CinemaRed else CinemaTextMuted)
                Spacer(Modifier.height(8.dp))
                Text("查看更多", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = if (f) CinemaRed else CinemaTextPrimary)
            }
        }
    }

    if (isTV) {
        Card(
            onClick = onClick,
            modifier = Modifier.width(dims.cardWidth).height(dims.cardHeight).onFocusChanged { f = it.isFocused },
            shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp)),
            border = CardDefaults.border(focusedBorder = Border(BorderStroke(2.dp, CinemaRed), 8.dp)),
            scale = CardDefaults.scale(focusedScale = 1f),
            colors = CardDefaults.colors(containerColor = CinemaSurface)
        ) { MoreCardContent() }
    } else {
        androidx.compose.material3.Card(
            onClick = onClick,
            modifier = Modifier.width(dims.cardWidth).height(dims.cardHeight),
            shape = RoundedCornerShape(8.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = CinemaSurface),
        ) { MoreCardContent() }
    }
}

// ═══════════════════════════════════════
// States
// ═══════════════════════════════════════

@Composable
private fun LoadingOverlay() {
    val dims = LocalDimensions.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DoplyLoadingIndicator(dims.loadingIndicatorSize)
            Spacer(Modifier.height(18.dp))
            Text("正在載入…", color = CinemaTextMuted, fontSize = 14.sp)
        }
    }
}

// ═══════════════════════════════════════
// More Sources (lazy-loaded MacCMS sites)
// ═══════════════════════════════════════

/** Configuration: which (source, category) combinations to surface as bonus rows.
 *  Picks differentiated content per source: imaple's anime depth, momovod's chinese drama
 *  pool, etc. Each row only fetches when LazyColumn composes it (= when user scrolls into view). */
private val MORE_SOURCES: List<Pair<SourceType, StandardCategory>> = listOf(
    SourceType.IMAPLE_TV to StandardCategory.ANIME,
    SourceType.MOMOVOD to StandardCategory.CHINESE,
    SourceType.KUBO123 to StandardCategory.KOREAN,
    SourceType.GIMY_TW to StandardCategory.CHINESE,
    SourceType.EYNY_TV to StandardCategory.MOVIE,
)

private fun categoryDisplayName(c: StandardCategory): String = when (c) {
    StandardCategory.MOVIE -> "電影"
    StandardCategory.SERIES -> "劇集"
    StandardCategory.ANIME -> "動漫"
    StandardCategory.VARIETY -> "綜藝"
    StandardCategory.KOREAN -> "韓劇"
    StandardCategory.CHINESE -> "陸劇"
    StandardCategory.HK -> "港劇"
    StandardCategory.TAIWAN -> "台劇"
    StandardCategory.JAPANESE -> "日劇"
    StandardCategory.AMERICAN -> "美劇"
    StandardCategory.DOCUMENTARY -> "紀錄片"
    StandardCategory.ADULT -> "倫理"
}

@Composable
private fun MoreSourcesHeader() {
    val dims = LocalDimensions.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 28.dp, bottom = 4.dp, start = dims.screenHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("✨", fontSize = 16.sp)
        Spacer(Modifier.width(8.dp))
        Text("更多來源", fontSize = 16.sp, fontWeight = FontWeight.Bold,
            color = CinemaTextPrimary, letterSpacing = 0.4.sp)
        Spacer(Modifier.width(8.dp))
        Text("｜內容池差異化推薦", fontSize = 11.sp, color = CinemaTextMuted)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MoreSourceRow(
    sourceType: SourceType,
    typeId: Int,
    title: String,
    onItemClick: (Vod) -> Unit,
    onMoreClick: () -> Unit,
    viewModel: HomeViewModel,
) {
    // collectAsState binds to ViewModel-cached StateFlow; first access kicks off fetch,
    // subsequent recompositions reuse cached items. refresh() clears the cache.
    val items by viewModel.moreSourceRow(sourceType, typeId).collectAsState()
    if (items.isEmpty()) return  // hide row until data arrives or after fetch fail
    ContentRow(title, typeId, sourceType, items,
        onItemClick = onItemClick,
        onMoreClick = onMoreClick,
    )
}

@Composable
private fun ErrorOverlay(error: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("載入失敗", color = CinemaTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(error, color = CinemaTextMuted, fontSize = 13.sp)
            Spacer(Modifier.height(20.dp))
            DoplyButton(onClick = onRetry, containerColor = CinemaRed) {
                Text("重試", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}
