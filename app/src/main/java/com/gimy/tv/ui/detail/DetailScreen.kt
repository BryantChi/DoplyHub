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
import com.gimy.tv.ui.theme.*

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DetailScreen(
    onPlayClick: (sourceType: String, vodId: Long, sourceId: Int, episodeNum: Int) -> Unit,
    onBack: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CinemaBase, CinemaBlack)))) {
        when {
            uiState.isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    GimyLoadingIndicator(48.dp)
                }
            }
            uiState.error != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(uiState.error!!, color = CinemaTextMuted, fontSize = 14.sp)
                        Spacer(Modifier.height(16.dp))
                        ActionButton("返回", false, onBack)
                    }
                }
            }
            uiState.detail != null -> {
                val d = uiState.detail!!
                var srcIdx by remember { mutableIntStateOf(0) }

                // Background blur image
                AsyncImage(
                    model = d.vod.coverUrl, contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(400.dp).graphicsLayer { alpha = 0.15f }
                )
                Box(Modifier.fillMaxWidth().height(400.dp).background(
                    Brush.verticalGradient(listOf(CinemaBlack.copy(0.3f), CinemaBlack))
                ))

                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
                    // ── Header ──
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 32.dp)) {
                            // Cover
                            AsyncImage(
                                model = d.vod.coverUrl, contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.width(175.dp).height(250.dp).clip(RoundedCornerShape(8.dp))
                            )
                            Spacer(Modifier.width(28.dp))
                            Column(Modifier.weight(1f)) {
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
                                    ) { viewModel.toggleFavorite() }

                                    if (uiState.lastEpisode != null && d.episodes.isNotEmpty()) {
                                        val ep = uiState.lastEpisode!!
                                        val sId = uiState.lastSourceId ?: d.episodes.first().sourceId
                                        var pf by remember { mutableStateOf(false) }
                                        Button(
                                            onClick = { onPlayClick(d.vod.sourceType.name, d.vod.id, sId, ep) },
                                            modifier = Modifier.onFocusChanged { pf = it.isFocused },
                                            shape = ButtonDefaults.shape(shape = RoundedCornerShape(6.dp)),
                                            colors = ButtonDefaults.colors(containerColor = CinemaRed, focusedContainerColor = Color.White),
                                            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 9.dp)
                                        ) { Text("▶ 續播第${ep}集", color = if (pf) CinemaBlack else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                                        ActionButton("刪除記錄", false) { viewModel.deleteHistory() }
                                    }
                                }

                                if (d.synopsis.isNotBlank()) {
                                    Spacer(Modifier.height(14.dp))
                                    Text(d.synopsis, color = CinemaTextMuted, fontSize = 13.sp,
                                        maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
                                }
                            }
                        }
                    }

                    // ── Source tabs ──
                    if (d.episodes.size > 1) {
                        item {
                            Column(Modifier.padding(horizontal = 48.dp)) {
                                Text("播放線路", color = CinemaTextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(8.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(d.episodes.size) { i ->
                                        val g = d.episodes[i]; val sel = i == srcIdx
                                        val label = if (i == 0) "${g.sourceName} ★" else g.sourceName
                                        var f by remember { mutableStateOf(false) }
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
                                    }
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                    }

                    // ── Episodes ──
                    if (d.episodes.isNotEmpty()) {
                        val grp = d.episodes.getOrNull(srcIdx) ?: d.episodes.first()
                        item {
                            EpisodeGrid(grp, uiState.lastEpisode) { sId, ep ->
                                onPlayClick(d.vod.sourceType.name, d.vod.id, sId, ep)
                            }
                        }
                    }
                }
            }
        }
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
    var f by remember { mutableStateOf(false) }
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
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeGrid(group: EpisodeGroup, lastEp: Int?, onEpClick: (Int, Int) -> Unit) {
    Column(Modifier.padding(horizontal = 48.dp)) {
        Text("選擇集數", color = CinemaTextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        for (row in group.episodes.chunked(14)) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (ep in row) {
                    val cur = lastEp != null && ep.number == lastEp
                    val watched = lastEp != null && ep.number < lastEp
                    var f by remember { mutableStateOf(false) }
                    Button(
                        onClick = { onEpClick(group.sourceId, ep.number) },
                        modifier = Modifier.width(56.dp).onFocusChanged { f = it.isFocused },
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(4.dp)),
                        colors = ButtonDefaults.colors(
                            containerColor = when { cur -> CinemaRed; watched -> CinemaRedDim.copy(0.5f); else -> CinemaSurface },
                            focusedContainerColor = CinemaRed
                        ),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        Text(ep.title.ifBlank { ep.number.toString() },
                            fontSize = 12.sp, color = Color.White,
                            fontWeight = if (cur || f) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
        }
    }
}
