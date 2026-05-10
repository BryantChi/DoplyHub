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
import com.gimy.tv.domain.util.prettifyVodStatus
import com.gimy.tv.ui.theme.*

/**
 * Per-source short tag + brand color for the top-left card badge.
 *
 * Tag = 2-4 char abbreviation (kept short so it fits the 9sp badge without
 * truncation on portrait cards). Color = visually distinct hue per scraper so
 * users can identify a card's origin at a glance in cross-source mixed grids.
 *
 * If a new SourceType is added, append a branch — Kotlin's exhaustive when
 * will flag it.
 */
private fun sourceBadgeFor(sourceType: SourceType): Pair<String, Color> = when (sourceType) {
    SourceType.GIMYTV -> "GTV" to Color(0xFF10B981)        // emerald
    SourceType.GIMYMAX -> "GMX" to Color(0xFFF97316)       // orange
    SourceType.MOVIEFFM -> "FFM" to Color(0xFF3B82F6)      // blue (legacy)
    SourceType.GIMY_TW -> "Gimy" to Color(0xFF6366F1)      // indigo
    SourceType.EYNY_TV -> "EY" to Color(0xFF06B6D4)        // cyan
    SourceType.IMAPLE_TV -> "IMP" to Color(0xFF8B5CF6)     // violet
    SourceType.MOMOVOD -> "MM" to Color(0xFFEC4899)        // pink
    SourceType.KUBO123 -> "KB" to Color(0xFFEAB308)        // yellow
    SourceType.JABLE_TV -> "JB" to Color(0xFFB91C1C)       // dark red
    SourceType.XNXX -> "XN" to Color(0xFF78350F)           // amber-900
    SourceType.FORUM5278 -> "5278" to Color(0xFFD97706)    // amber-600
}

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
        // Top-left: per-source badge — every scraper gets its own short tag +
        // brand color so users can tell at a glance which site a card came from
        // (matters when the same show appears across multiple sources in search /
        // adult-plus mixed grids). Color pairs picked to be visually distinct
        // without clashing against the red status badge in the top-right.
        val (sourceTag, sourceColor) = sourceBadgeFor(vod.sourceType)
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(4.dp)
                .background(sourceColor.copy(0.92f), RoundedCornerShape(3.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp)
        ) {
            Text(sourceTag, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        // Top-right: status badge — normalize via prettifyVodStatus so home/list
        // grids don't show the source-specific hodgepodge ("更新至第33集" vs
        // "38集全" vs "更新26" vs "HD") that the user reads as "the count is
        // wrong even when the underlying number is correct.
        val prettyStatus = remember(vod.status) { prettifyVodStatus(vod.status) }
        if (prettyStatus.isNotBlank()) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(CinemaRed.copy(0.92f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(prettyStatus, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
    /** When true, the card stretches to its parent's width (used by LazyVerticalGrid cells)
     *  with height computed from aspect ratio. When false, the card uses fixed dims-based
     *  width/height (used by LazyRow rails on Home/AdultPlus). */
    fillCellWidth: Boolean = false,
) {
    val dims = LocalDimensions.current
    val isTV = LocalIsTelevision.current
    val cardW = if (landscape) (dims.cardWidth.value * 1.55f).dp else dims.cardWidth
    val cardH = if (landscape) (cardW.value * 9f / 16f).dp + 24.dp else dims.cardHeight

    val sizeMod = if (fillCellWidth) {
        // Stretch to grid cell width; aspect ratio matches the card style. Title overlays
        // the bottom of the image (see VodCardContent), so 16:9 / 2:3 alone is enough.
        Modifier
            .fillMaxWidth()
            .aspectRatio(if (landscape) 16f / 9f else dims.cardWidth.value / dims.cardHeight.value)
    } else {
        Modifier.width(cardW).height(cardH)
    }

    if (isTV) {
        var focused by remember { mutableStateOf(false) }

        androidx.tv.material3.Card(
            onClick = onClick,
            onLongClick = onLongClick ?: {},
            modifier = modifier
                .then(sizeMod)
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
                .then(sizeMod)
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
