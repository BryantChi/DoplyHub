package com.gimy.tv.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.gimy.tv.ui.components.DoplyLoadingIndicator
import com.gimy.tv.ui.components.RefreshIconButton
import com.gimy.tv.ui.components.RefreshLoadingBar
import com.gimy.tv.ui.components.RefreshableContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.gimy.tv.domain.model.EpisodeGroup
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.domain.model.VodDetail
import com.gimy.tv.domain.model.displayName
import com.gimy.tv.ui.components.VodCard
import com.gimy.tv.ui.theme.*

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DetailScreen(
    onPlayClick: (sourceType: String, vodId: Long, sourceId: Int, episodeNum: Int) -> Unit,
    onVodClick: (SourceType, Long) -> Unit = { _, _ -> },
    onBack: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel()
) {
    val dims = LocalDimensions.current
    val isTV = LocalIsTelevision.current
    val uiState by viewModel.uiState.collectAsState()

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CinemaBase, CinemaBlack)))) {
        RefreshLoadingBar(
            isRefreshing = uiState.isRefreshing,
            modifier = Modifier.align(Alignment.TopCenter)
        )
        when {
            uiState.isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    DoplyLoadingIndicator(dims.loadingIndicatorSize)
                }
            }
            uiState.error != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(uiState.error ?: "", color = CinemaTextMuted, fontSize = 14.sp)
                        Spacer(Modifier.height(16.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ActionButton("返回", false, onBack)
                            ActionButton("重試", false) { viewModel.refresh() }
                            if (uiState.lastEpisode != null) {
                                ActionButton("刪除記錄", false) { viewModel.deleteHistory() }
                            }
                        }
                    }
                }
            }
            uiState.detail != null -> {
                val d = uiState.detail ?: return
                var srcIdx by remember { mutableIntStateOf(0) }

                // jable/xnxx/5278 all ship widescreen 16:9 thumbnails; the default 2:3
                // portrait cover box + ContentScale.Crop clips ~50% off each side. Use a
                // wider 16:9 box for these sources. 2.0x makes the cover visually prominent
                // (TV: 350×197, phone: 240×135) — earlier 1.5x looked too small next to
                // the metadata column.
                val isLandscapeCover = d.vod.sourceType == SourceType.JABLE_TV ||
                    d.vod.sourceType == SourceType.XNXX ||
                    d.vod.sourceType == SourceType.FORUM5278
                val coverW = if (isLandscapeCover) (dims.coverWidth.value * 2.0f).dp
                    else dims.coverWidth
                val coverH = if (isLandscapeCover) (coverW.value * 9f / 16f).dp
                    else dims.coverHeight

                // Cross-source filter (Phase 3.3): user can narrow episodes to a single source.
                // Source labels are parsed from the [Prefix] in EpisodeGroup.sourceName that
                // VodRepositoryImpl.getEnrichedVodDetail injects for secondary sources.
                val primaryDisplayName = d.vod.sourceType.displayName
                val sourceLabels = remember(d.episodes, primaryDisplayName) {
                    d.episodes.map { extractGroupSource(it.sourceName, primaryDisplayName) }.distinct()
                }
                var selectedSourceLabel by remember(d.vod.id) { mutableStateOf<String?>(null) }
                val filteredEpisodes = remember(d.episodes, selectedSourceLabel, primaryDisplayName) {
                    if (selectedSourceLabel == null) d.episodes
                    else d.episodes.filter { extractGroupSource(it.sourceName, primaryDisplayName) == selectedSourceLabel }
                }
                // Reset srcIdx when filter switches (so we don't point to a now-invisible group)
                LaunchedEffect(selectedSourceLabel) { srcIdx = 0 }
                val safeSrcIdx = if (filteredEpisodes.isNotEmpty()) srcIdx.coerceIn(0, filteredEpisodes.size - 1) else 0

                // Background hero: blurred cover behind a gradient. We bump both the
                // bleed area and the alpha so the cover comes through more visibly while
                // keeping content legible — the gradient stops are tuned so the bottom
                // half still fades cleanly into pure CinemaBlack and doesn't fight the
                // metadata column underneath.
                Box(Modifier.fillMaxWidth().height(if (isTV) 680.dp else 460.dp)) {
                    AsyncImage(
                        model = d.vod.coverUrl, contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alpha = 0.32f,
                        modifier = Modifier.fillMaxSize()
                    )
                    // 4-stop gradient: keep the top airy, extend the soft mid-band so the
                    // blur fades naturally over the larger area without an abrupt cut, then
                    // pin the bottom to pure CinemaBlack so the metadata column underneath
                    // sits on a solid background.
                    Box(Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to CinemaBlack.copy(0.08f),
                            0.45f to CinemaBlack.copy(0.40f),
                            0.80f to CinemaBlack.copy(0.85f),
                            1f to CinemaBlack,
                        )
                    ))
                }

                val detailListState = rememberLazyListState()
                val detailIsAtTop by remember {
                    derivedStateOf {
                        detailListState.firstVisibleItemIndex == 0 && detailListState.firstVisibleItemScrollOffset == 0
                    }
                }
                RefreshableContainer(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    enabled = detailIsAtTop,
                ) {
                LazyColumn(state = detailListState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
                    // ── Header ──
                    item {
                        if (isTV) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = dims.screenHorizontalPadding, vertical = 32.dp)
                            ) {
                                // Cover
                                AsyncImage(
                                    model = d.vod.coverUrl, contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .width(coverW)
                                        .height(coverH)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                                Spacer(Modifier.width(28.dp))
                                Column(Modifier.weight(1f)) {
                                    DetailInfo(
                                        d = d,
                                        uiState = uiState,
                                        onBack = onBack,
                                        onPlayClick = onPlayClick,
                                        onToggleFavorite = { viewModel.toggleFavorite() },
                                        onDeleteHistory = { viewModel.deleteHistory() },
                                        onRefresh = { viewModel.refresh() },
                                    )
                                }
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .padding(horizontal = dims.screenHorizontalPadding, vertical = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                // Cover image
                                AsyncImage(
                                    model = d.vod.coverUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .width(coverW)
                                        .height(coverH)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                                Spacer(Modifier.height(12.dp))
                                DetailInfo(
                                    d = d,
                                    uiState = uiState,
                                    onBack = onBack,
                                    onPlayClick = onPlayClick,
                                    onToggleFavorite = { viewModel.toggleFavorite() },
                                    onDeleteHistory = { viewModel.deleteHistory() },
                                    onRefresh = { viewModel.refresh() },
                                )
                            }
                        }
                    }

                    // ── Source filter (Phase 3.3) ──
                    if (sourceLabels.size > 1) {
                        item {
                            Column(Modifier.padding(horizontal = dims.screenHorizontalPadding)) {
                                Text("選擇來源", color = CinemaTextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(8.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    item(key = "src_chip_all") {
                                        DetailSourceChip(
                                            label = "全部 ${d.episodes.size}",
                                            selected = selectedSourceLabel == null,
                                            onClick = { selectedSourceLabel = null },
                                        )
                                    }
                                    items(sourceLabels, key = { "src_chip_$it" }) { lbl ->
                                        val count = d.episodes.count { extractGroupSource(it.sourceName, primaryDisplayName) == lbl }
                                        DetailSourceChip(
                                            label = "$lbl $count",
                                            selected = selectedSourceLabel == lbl,
                                            onClick = { selectedSourceLabel = if (selectedSourceLabel == lbl) null else lbl },
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }

                    // ── Source tabs ──
                    if (filteredEpisodes.size > 1) {
                        item {
                            Column(Modifier.padding(horizontal = dims.screenHorizontalPadding)) {
                                Text("播放線路", color = CinemaTextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(8.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(filteredEpisodes.size) { i ->
                                        val g = filteredEpisodes[i]; val sel = i == safeSrcIdx
                                        val label = if (i == 0) "${g.sourceName} ★" else g.sourceName
                                        var f by remember { mutableStateOf(false) }
                                        if (isTV) {
                                            Button(
                                                onClick = { srcIdx = i },
                                                modifier = Modifier.onFocusChanged { f = it.isFocused },
                                                shape = ButtonDefaults.shape(shape = RoundedCornerShape(5.dp)),
                                                colors = ButtonDefaults.colors(
                                                    containerColor = when { sel -> CinemaRed; f -> CinemaRed.copy(0.5f); else -> CinemaSurface },
                                                    focusedContainerColor = if (sel) CinemaRed else CinemaRed.copy(0.5f)
                                                ),
                                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp)
                                            ) { Text(label, color = Color.White, fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal) }
                                        } else {
                                            androidx.compose.material3.Button(
                                                onClick = { srcIdx = i },
                                                shape = RoundedCornerShape(5.dp),
                                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                                    containerColor = if (sel) CinemaRed else CinemaSurface,
                                                    contentColor = Color.White,
                                                ),
                                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp)
                                            ) { Text(label, color = Color.White, fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal) }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                    }

                    // ── Episodes ──
                    if (filteredEpisodes.isNotEmpty()) {
                        val grp = filteredEpisodes[safeSrcIdx]
                        item {
                            EpisodeGrid(grp, uiState.lastEpisode, dims.episodeColumns) { sId, ep ->
                                onPlayClick(d.vod.sourceType.name, d.vod.id, sId, ep)
                            }
                        }
                    }

                    // Related series (same franchise, different seasons)
                    if (d.seriesVods.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(24.dp))
                            RelatedRow("相關系列", d.seriesVods, dims.screenHorizontalPadding, onVodClick)
                        }
                    }

                    // Related/recommended content
                    if (d.relatedVods.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(24.dp))
                            RelatedRow("相關推薦", d.relatedVods, dims.screenHorizontalPadding, onVodClick)
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
private fun DetailInfo(
    d: VodDetail,
    uiState: DetailUiState,
    onBack: () -> Unit,
    onPlayClick: (sourceType: String, vodId: Long, sourceId: Int, episodeNum: Int) -> Unit,
    onToggleFavorite: () -> Unit,
    onDeleteHistory: () -> Unit,
    onRefresh: () -> Unit,
) {
    val isTV = LocalIsTelevision.current
    // Phone hero is laid out as a centered column above the cover. Without explicit
    // textAlign + fillMaxWidth, short titles get auto-centered (Column align rule)
    // but long titles size to fill, leaving Text-internal alignment Start → "looks left".
    // Force Text content alignment so behavior is identical regardless of length.
    val titleAlign = if (isTV) TextAlign.Start else TextAlign.Center
    val rowArrange = if (isTV) Arrangement.spacedBy(8.dp) else Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    val actionArrange = if (isTV) Arrangement.spacedBy(10.dp) else Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
    val widthMod = if (isTV) Modifier else Modifier.fillMaxWidth()

    Text(d.vod.title, color = Color.White, fontSize = 26.sp,
        fontWeight = FontWeight.Black, letterSpacing = (-0.3).sp,
        textAlign = titleAlign, modifier = widthMod)

    Spacer(Modifier.height(8.dp))
    Row(modifier = widthMod, horizontalArrangement = rowArrange) {
        if (d.vod.status.isNotBlank()) InfoBadge(d.vod.status, CinemaRed)
        if (d.vod.year > 0) InfoBadge(d.vod.year.toString(), CinemaSurface)
        if (d.vod.category.isNotBlank()) InfoBadge(d.vod.category, CinemaSurface)
    }

    Spacer(Modifier.height(12.dp))
    if (d.director.isNotBlank()) MetaLine("導演", d.director, isTV)
    if (d.actors.isNotEmpty()) MetaLine("主演", d.actors.take(5).joinToString(" / "), isTV)

    Spacer(Modifier.height(16.dp))
    FlowRow(
        modifier = widthMod,
        horizontalArrangement = actionArrange,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActionButton("返回", false, onBack)
        ActionButton(
            if (uiState.isFavorite) "已收藏" else "收藏",
            uiState.isFavorite
        ) { onToggleFavorite() }
        RefreshIconButton(isRefreshing = uiState.isRefreshing, onClick = onRefresh)

        val lastEp = uiState.lastEpisode
        if (lastEp != null && d.episodes.isNotEmpty()) {
            val ep = lastEp
            val sId = uiState.lastSourceId ?: d.episodes.firstOrNull()?.sourceId ?: 0
            var pf by remember { mutableStateOf(false) }
            if (isTV) {
                Button(
                    onClick = { onPlayClick(d.vod.sourceType.name, d.vod.id, sId, ep) },
                    modifier = Modifier.onFocusChanged { pf = it.isFocused },
                    shape = ButtonDefaults.shape(shape = RoundedCornerShape(6.dp)),
                    colors = ButtonDefaults.colors(containerColor = CinemaRed, focusedContainerColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 9.dp)
                ) { Text("▶ 續播第${ep}集", color = if (pf) CinemaBlack else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
            } else {
                androidx.compose.material3.Button(
                    onClick = { onPlayClick(d.vod.sourceType.name, d.vod.id, sId, ep) },
                    shape = RoundedCornerShape(6.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = CinemaRed, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 9.dp)
                ) { Text("▶ 續播第${ep}集", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
            }
            ActionButton("刪除記錄", false) { onDeleteHistory() }
        }
    }

    if (d.synopsis.isNotBlank()) {
        Spacer(Modifier.height(14.dp))
        Text(d.synopsis, color = CinemaTextMuted, fontSize = 13.sp,
            maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp,
            textAlign = titleAlign, modifier = widthMod)
    }
}

@Composable
private fun InfoBadge(text: String, bg: Color) {
    Box(Modifier.background(bg.copy(0.85f), RoundedCornerShape(3.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MetaLine(label: String, value: String, isTV: Boolean = true) {
    val arrangement = if (isTV) Arrangement.Start else Arrangement.Center
    val widthMod = if (isTV) Modifier else Modifier.fillMaxWidth()
    Row(widthMod.padding(vertical = 1.dp), horizontalArrangement = arrangement) {
        Text("$label  ", color = CinemaTextMuted, fontSize = 13.sp)
        Text(value, color = CinemaTextPrimary.copy(0.8f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ActionButton(label: String, active: Boolean, onClick: () -> Unit) {
    val isTV = LocalIsTelevision.current
    var f by remember { mutableStateOf(false) }
    if (isTV) {
        Button(
            onClick = onClick,
            modifier = Modifier.onFocusChanged { f = it.isFocused },
            shape = ButtonDefaults.shape(shape = RoundedCornerShape(6.dp)),
            colors = ButtonDefaults.colors(
                containerColor = if (active) CinemaRedDim else CinemaSurface,
                focusedContainerColor = CinemaRed
            ),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 9.dp)
        ) { Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
    } else {
        androidx.compose.material3.Button(
            onClick = onClick,
            modifier = Modifier,
            shape = RoundedCornerShape(6.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = if (active) CinemaRedDim else CinemaSurface,
                contentColor = Color.White,
            ),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 9.dp)
        ) { Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RelatedRow(
    title: String,
    relatedVods: List<Vod>,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    onVodClick: (SourceType, Long) -> Unit
) {
    Column(Modifier.padding(horizontal = horizontalPadding)) {
        Text(title, color = CinemaTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(relatedVods.size, key = { "rel_${it}_${relatedVods[it].sourceType}_${relatedVods[it].id}" }) { idx ->
                val vod = relatedVods[idx]
                VodCard(vod = vod, onClick = { onVodClick(vod.sourceType, vod.id) })
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeGrid(
    group: EpisodeGroup,
    lastEp: Int?,
    episodeColumns: Int,
    onEpClick: (Int, Int) -> Unit
) {
    val isTV = LocalIsTelevision.current
    val dims = LocalDimensions.current
    Column(Modifier.fillMaxWidth().padding(horizontal = dims.screenHorizontalPadding)) {
        Text("選擇集數", color = CinemaTextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        for (row in group.episodes.chunked(episodeColumns)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                for (ep in row) {
                    key(ep.number) {
                    val cur = lastEp != null && ep.number == lastEp
                    val watched = lastEp != null && ep.number < lastEp
                    var f by remember { mutableStateOf(false) }
                    val epColor = when { cur -> CinemaRed; watched -> CinemaRedDim.copy(0.5f); else -> CinemaSurface }
                    // Compact label: long titles like "特別篇 - 大結局" get truncated to
                    // ep.number to keep button heights uniform and avoid awkward 中文 character
                    // breaks. Threshold of 5 chars matches "第01集" / "第123集" / 番外篇.
                    val rawLabel = ep.title.ifBlank { ep.number.toString() }
                    val label = if (rawLabel.length > 5) ep.number.toString() else rawLabel
                    if (isTV) {
                        Button(
                            onClick = { onEpClick(group.sourceId, ep.number) },
                            modifier = Modifier.weight(1f).heightIn(min = 36.dp).onFocusChanged { f = it.isFocused },
                            shape = ButtonDefaults.shape(shape = RoundedCornerShape(4.dp)),
                            colors = ButtonDefaults.colors(containerColor = epColor, focusedContainerColor = CinemaRed),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                        ) {
                            Text(label,
                                fontSize = 12.sp, color = Color.White,
                                fontWeight = if (cur || f) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false,
                                textAlign = TextAlign.Center)
                        }
                    } else {
                        androidx.compose.material3.Button(
                            onClick = { onEpClick(group.sourceId, ep.number) },
                            modifier = Modifier.weight(1f).heightIn(min = 36.dp),
                            shape = RoundedCornerShape(4.dp),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = epColor, contentColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                        ) {
                            Text(label,
                                fontSize = 12.sp, color = Color.White,
                                fontWeight = if (cur) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false,
                                textAlign = TextAlign.Center)
                        }
                    }
                    } // key
                }
                // Fill remaining space when last row has fewer items
                val remaining = episodeColumns - row.size
                if (remaining > 0) {
                    repeat(remaining) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(5.dp))
        }
    }
}

// ═══════════════════════════════════════
// Cross-source filter (Phase 3.3)
// ═══════════════════════════════════════

/** Extract the owner source label from EpisodeGroup.sourceName.
 *  Cross-source groups carry a "[SourceDisplayName] " prefix injected by
 *  VodRepositoryImpl.getEnrichedVodDetail. Primary groups have no prefix —
 *  for those we return the primary's own displayName so the chip can target them. */
private fun extractGroupSource(sourceName: String, primaryDisplayName: String): String {
    val match = Regex("^\\[([^\\]]+)\\]").find(sourceName)
    return match?.groupValues?.get(1) ?: primaryDisplayName
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DetailSourceChip(label: String, selected: Boolean, onClick: () -> Unit) {
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
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        ) { Text(label, color = Color.White, fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) }
    } else {
        androidx.compose.material3.Button(
            onClick = onClick,
            shape = RoundedCornerShape(20.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = container, contentColor = Color.White),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        ) { Text(label, color = Color.White, fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) }
    }
}
