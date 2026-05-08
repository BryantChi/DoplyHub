package com.gimy.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.tv.material3.Border
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import coil.compose.AsyncImage
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.ui.theme.*

@Composable
private fun VodCardContent(vod: Vod) {
    Box {
        AsyncImage(
            model = vod.coverUrl, contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // Bottom gradient for text readability
        Box(
            Modifier
                .fillMaxWidth()
                .height(82.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.2f to CinemaBlack.copy(0.5f),
                        1f to CinemaBlack.copy(0.95f)
                    )
                )
        )
        // Title + metadata at bottom
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .fillMaxWidth()
        ) {
            Text(
                vod.title, color = CinemaTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp
            )
            // Subtitle row: year + rating + category
            val subtitle = remember(vod.year, vod.rating, vod.category) {
                buildList {
                    if (vod.year > 0) add(vod.year.toString())
                    vod.rating?.let { add("★%.1f".format(it)) }
                    if (vod.category.isNotBlank()) add(vod.category)
                }.joinToString(" · ")
            }
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
                Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .background(Color(0xFF3B82F6).copy(0.92f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text("FFM", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
        // Top-right: status badge
        if (vod.status.isNotBlank()) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(CinemaRed.copy(0.92f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(vod.status, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VodCard(
    vod: Vod,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** Use 16:9 widescreen aspect for sources whose thumbnails are landscape (jable / xnxx).
     *  The default 2:3 portrait fits typical poster art; using Crop on a 16:9 source clips
     *  most of the image. Pass landscape = true so the card matches the source aspect. */
    landscape: Boolean = false,
) {
    val dims = LocalDimensions.current
    val isTV = LocalIsTelevision.current
    // 16:9 cards are a touch wider than the default poster card so they remain readable
    val cardW = if (landscape) (dims.cardWidth.value * 1.55f).dp else dims.cardWidth
    val cardH = if (landscape) (cardW.value * 9f / 16f).dp + 24.dp else dims.cardHeight  // +24dp for title strip

    if (isTV) {
        var focused by remember { mutableStateOf(false) }

        androidx.tv.material3.Card(
            onClick = onClick,
            onLongClick = onLongClick ?: {},
            modifier = modifier
                .width(cardW)
                .height(cardH)
                .onFocusChanged { focused = it.isFocused },
            shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp)),
            scale = CardDefaults.scale(focusedScale = 1.05f),
            border = CardDefaults.border(
                focusedBorder = Border(BorderStroke(2.dp, CinemaRed), 8.dp)
            ),
            colors = CardDefaults.colors(containerColor = CinemaCard)
        ) {
            VodCardContent(vod)
        }
    } else {
        androidx.compose.material3.Card(
            modifier = modifier
                .width(cardW)
                .height(cardH)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
            shape = RoundedCornerShape(8.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = CinemaCard),
        ) {
            VodCardContent(vod)
        }
    }
}
