package com.gimy.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.ui.theme.*

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VodCard(
    vod: Vod,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        onLongClick = onLongClick ?: {},
        modifier = modifier
            .width(154.dp)
            .height(248.dp)
            .onFocusChanged { focused = it.isFocused },
        shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp)),
        scale = CardDefaults.scale(focusedScale = 1.05f),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, CinemaRed), 8.dp)
        ),
        colors = CardDefaults.colors(containerColor = CinemaCard)
    ) {
        Box {
            AsyncImage(
                model = vod.coverUrl, contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // Bottom gradient for text readability
            Box(
                Modifier.fillMaxWidth().height(82.dp).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(
                        0f to Color.Transparent, 0.2f to CinemaBlack.copy(0.5f), 1f to CinemaBlack.copy(0.95f)
                    ))
            )
            // Title + metadata at bottom
            Column(
                Modifier.align(Alignment.BottomStart)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    vod.title, color = CinemaTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp
                )
                // Subtitle row: year + rating + category
                val subtitle = buildList {
                    if (vod.year > 0) add(vod.year.toString())
                    vod.rating?.let { add("★%.1f".format(it)) }
                    if (vod.category.isNotBlank()) add(vod.category)
                }.joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle, color = CinemaTextMuted, fontSize = 10.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // Top-left: source badge for movieffm
            if (vod.sourceType == SourceType.MOVIEFFM) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(4.dp)
                        .background(Color(0xFF3B82F6).copy(0.92f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text("FFM", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
            // Top-right: status badge
            if (vod.status.isNotBlank()) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp)
                        .background(CinemaRed.copy(0.92f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(vod.status, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
