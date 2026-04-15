package com.gimy.tv.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import com.gimy.tv.ui.components.GimyLoadingIndicator
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
        when {
            uiState.isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    GimyLoadingIndicator(dims.loadingIndicatorSize)
                }
            }
            uiState.error != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(uiState.error ?: "", color = CinemaTextMuted, fontSize = 14.sp)
                        Spacer(Modifier.height(16.dp))
                        ActionButton("返回", false, onBack)
                    }
                }
            }
            uiState.detail != null -> {
                val d = uiState.detail ?: return
                var srcIdx by remember { mutableIntStateOf(0) }
                // Clamp srcIdx when episodes list changes (e.g. after enrichment)
                val safeSrcIdx = if (d.episodes.isNotEmpty()) srcIdx.coerceIn(0, d.episodes.size - 1) else 0

                // Background blur image with overlay gradient (single layer to reduce overdraw)
                Box(Modifier.fillMaxWidth().height(if (isTV) 400.dp else 250.dp)) {
                    AsyncImage(
                        model = d.vod.coverUrl, contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alpha = 0.15f,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(CinemaBlack.copy(0.3f), CinemaBlack))
                    ))
                }

                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
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
                                        .width(dims.coverWidth)
                                        .height(dims.coverHeight)
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
                                        onDeleteHistory = { viewModel.deleteHistory() }
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
                                        .width(dims.coverWidth)
                                        .height(dims.coverHeight)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                                Spacer(Modifier.height(12.dp))
                                DetailInfo(
                                    d = d,
                                    uiState = uiState,
                                    onBack = onBack,
                                    onPlayClick = onPlayClick,
                                    onToggleFavorite = { viewModel.toggleFavorite() },
                                    onDeleteHistory = { viewModel.deleteHistory() }
                                )
                            }
                        }
                    }

                    // ── Source tabs ──
                    if (d.episodes.size > 1) {
                        item {
                            Column(Modifier.padding(horizontal = dims.screenHorizontalPadding)) {
                                Text("播放線路", color = CinemaTextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(8.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(d.episodes.size) { i ->
                                        val g = d.episodes[i]; val sel = i == safeSrcIdx
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
                    if (d.episodes.isNotEmpty()) {
                        val grp = d.episodes[safeSrcIdx]
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DetailInfo(
    d: VodDetail,
    uiState: DetailUiState,
    onBack: () -> Unit,
    onPlayClick: (sourceType: String, vodId: Long, sourceId: Int, episodeNum: Int) -> Unit,
    onToggleFavorite: () -> Unit,
    onDeleteHistory: () -> Unit,
) {
    val isTV = LocalIsTelevision.current
    Text(d.vod.title, color = Color.White, fontSize = 26.sp,
        fontWeight = FontWeight.Black, letterSpacing = (-0.3).sp)

    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (d.vod.status.isNotBlank()) InfoBadge(d.vod.status, CinemaRed)
        if (d.vod.year > 0) InfoBadge(d.vod.year.toString(), CinemaSurface)
        if (d.vod.category.isNotBlank()) InfoBadge(d.vod.category, CinemaSurface)
    }

    Spacer(Modifier.height(12.dp))
    if (d.director.isNotBlank()) MetaLine("導演", d.director)
    if (d.actors.isNotEmpty()) MetaLine("主演", d.actors.take(5).joinToString(" / "))

    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionButton("返回", false, onBack)
        ActionButton(
            if (uiState.isFavorite) "已收藏" else "收藏",
            uiState.isFavorite
        ) { onToggleFavorite() }

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
            maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
    }
}

@Composable
private fun InfoBadge(text: String, bg: Color) {
    Box(Modifier.background(bg.copy(0.85f), RoundedCornerShape(3.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MetaLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 1.dp)) {
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
    Column(Modifier.padding(horizontal = dims.screenHorizontalPadding)) {
        Text("選擇集數", color = CinemaTextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        for (row in group.episodes.chunked(episodeColumns)) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (ep in row) {
                    key(ep.number) {
                    val cur = lastEp != null && ep.number == lastEp
                    val watched = lastEp != null && ep.number < lastEp
                    var f by remember { mutableStateOf(false) }
                    val epColor = when { cur -> CinemaRed; watched -> CinemaRedDim.copy(0.5f); else -> CinemaSurface }
                    if (isTV) {
                        Button(
                            onClick = { onEpClick(group.sourceId, ep.number) },
                            modifier = Modifier.width(56.dp).onFocusChanged { f = it.isFocused },
                            shape = ButtonDefaults.shape(shape = RoundedCornerShape(4.dp)),
                            colors = ButtonDefaults.colors(containerColor = epColor, focusedContainerColor = CinemaRed),
                            contentPadding = PaddingValues(4.dp)
                        ) {
                            Text(ep.title.ifBlank { ep.number.toString() },
                                fontSize = 12.sp, color = Color.White,
                                fontWeight = if (cur || f) FontWeight.Bold else FontWeight.Normal)
                        }
                    } else {
                        androidx.compose.material3.Button(
                            onClick = { onEpClick(group.sourceId, ep.number) },
                            modifier = Modifier.width(44.dp),
                            shape = RoundedCornerShape(4.dp),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = epColor, contentColor = Color.White),
                            contentPadding = PaddingValues(4.dp)
                        ) {
                            Text(ep.title.ifBlank { ep.number.toString() },
                                fontSize = 12.sp, color = Color.White,
                                fontWeight = if (cur) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                    } // key
                }
            }
            Spacer(Modifier.height(5.dp))
        }
    }
}
