package com.gimy.tv.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import com.gimy.tv.ui.components.GimyLoadingIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged
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
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.ui.components.VodCard
import com.gimy.tv.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    onVodClick: (SourceType, Long) -> Unit,
    onSearchClick: () -> Unit,
    onBrowseClick: (SourceType, Int) -> Unit,
    onFavoritesClick: () -> Unit,
    onHistoryClick: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(CinemaBase, CinemaBlack, CinemaBase)))
    ) {
        when {
            uiState.isLoading -> LoadingOverlay()
            uiState.error != null -> ErrorOverlay(uiState.error!!) { viewModel.loadHome() }
            else -> {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
                    // ── Top bar ──
                    item { TopBar(onSearchClick, onFavoritesClick, onHistoryClick) }

                    // ── Hero banner ──
                    val heroItems = uiState.rows.firstOrNull()?.items?.take(6) ?: emptyList()
                    if (heroItems.isNotEmpty()) {
                        item { HeroBanner(heroItems) { onVodClick(it.sourceType, it.id) } }
                    }

                    // ── Continue watching ──
                    if (uiState.continueWatching.isNotEmpty()) {
                        item {
                            ContentRow("繼續觀看", 0, SourceType.GIMYMAX,
                                uiState.continueWatching.map { e ->
                                    Vod(e.vodId, e.sourceType, e.title, e.coverUrl, "", 0, "第${e.episodeNum}集")
                                },
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
                }
            }
        }
    }
}

// ═══════════════════════════════════════
// Top navigation
// ═══════════════════════════════════════

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TopBar(onSearch: () -> Unit, onFav: () -> Unit, onHistory: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Brand
        Row(verticalAlignment = Alignment.Bottom) {
            Text("G", fontSize = 30.sp, fontWeight = FontWeight.Black, color = CinemaRed)
            Text("IMY", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = CinemaTextPrimary, modifier = Modifier.offset(x = (-2).dp))
            Spacer(Modifier.width(4.dp))
            Text("TV", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = CinemaTextMuted,
                modifier = Modifier.offset(y = (-2).dp).background(CinemaRed.copy(0.15f), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NavChip("搜尋", onSearch)
            NavChip("收藏", onFav)
            NavChip("歷史", onHistory)
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
            .padding(horizontal = 48.dp, vertical = 6.dp)
            .height(340.dp)
            .clip(RoundedCornerShape(12.dp))
            .bringIntoViewRequester(bringIntoViewRequester)
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
                        .padding(start = 48.dp, top = 32.dp, bottom = 80.dp)
                        .fillMaxHeight()
                        .fillMaxWidth(0.38f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("${currentIdx + 1} / ${items.size}", color = CinemaRed, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(CinemaRed.copy(0.15f), RoundedCornerShape(3.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(vod.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 32.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (vod.year > 0) {
                            Text("${vod.year}", color = CinemaTextMuted, fontSize = 13.sp)
                        }
                        if (vod.category.isNotBlank()) {
                            Text(vod.category, color = CinemaTextMuted, fontSize = 13.sp)
                        }
                        if (vod.status.isNotBlank()) {
                            Text(vod.status, color = CinemaGold, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // ── Button: outside Crossfade so focus persists across transitions ──
        var btnFocused by remember { mutableStateOf(false) }
        Button(
            onClick = { onItemClick(items[idx]) },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 96.dp, bottom = 44.dp)
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
// Content row
// ═══════════════════════════════════════

private val MovieffmBlue = Color(0xFF3B82F6)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ContentRow(title: String, typeId: Int, rowSourceType: SourceType, items: List<Vod>, onItemClick: (Vod) -> Unit, onMoreClick: () -> Unit) {
    val isMovieffm = rowSourceType == SourceType.MOVIEFFM
    val accentColor = if (isMovieffm) MovieffmBlue else CinemaRed

    Column(Modifier.padding(top = 20.dp)) {
        Row(Modifier.padding(start = 48.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
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
    var f by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = Modifier
            .width(154.dp)
            .height(248.dp)
            .onFocusChanged { f = it.isFocused },
        shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp)),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(2.dp, CinemaRed), 8.dp)),
        scale = CardDefaults.scale(focusedScale = 1f),
        colors = CardDefaults.colors(containerColor = CinemaSurface)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("▶", fontSize = 28.sp, color = if (f) CinemaRed else CinemaTextMuted)
                Spacer(Modifier.height(8.dp))
                Text("查看更多", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = if (f) CinemaRed else CinemaTextPrimary)
            }
        }
    }
}

// ═══════════════════════════════════════
// States
// ═══════════════════════════════════════

@Composable
private fun LoadingOverlay() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            GimyLoadingIndicator(48.dp)
            Spacer(Modifier.height(18.dp))
            Text("正在載入…", color = CinemaTextMuted, fontSize = 14.sp)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ErrorOverlay(error: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("載入失敗", color = CinemaTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(error, color = CinemaTextMuted, fontSize = 13.sp)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRetry, colors = ButtonDefaults.colors(containerColor = CinemaRed)) {
                Text("重試", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}
