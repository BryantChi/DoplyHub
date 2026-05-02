package com.gimy.tv.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class DoplyDimensions(
    val screenHorizontalPadding: Dp,
    val screenVerticalPadding: Dp,
    val cardWidth: Dp,
    val cardHeight: Dp,
    val cardSpacing: Dp,
    val heroBannerHeight: Dp,
    val heroBannerTitleSize: TextUnit,
    val heroBannerMetaSize: TextUnit,
    val heroBannerTextWidthFraction: Float,
    val coverWidth: Dp,
    val coverHeight: Dp,
    val gridMinCellWidth: Dp,
    val episodeColumns: Int,
    val loadingIndicatorSize: Dp,
)

val TvDimensions = DoplyDimensions(
    screenHorizontalPadding = 48.dp,
    screenVerticalPadding = 20.dp,
    cardWidth = 154.dp,
    cardHeight = 248.dp,
    cardSpacing = 14.dp,
    heroBannerHeight = 340.dp,
    heroBannerTitleSize = 26.sp,
    heroBannerMetaSize = 13.sp,
    heroBannerTextWidthFraction = 0.38f,
    coverWidth = 175.dp,
    coverHeight = 250.dp,
    gridMinCellWidth = 166.dp,
    episodeColumns = 14,
    loadingIndicatorSize = 48.dp,
)

val TabletDimensions = DoplyDimensions(
    screenHorizontalPadding = 32.dp,
    screenVerticalPadding = 18.dp,
    cardWidth = 150.dp,
    cardHeight = 230.dp,
    cardSpacing = 14.dp,
    heroBannerHeight = 300.dp,
    heroBannerTitleSize = 24.sp,
    heroBannerMetaSize = 13.sp,
    heroBannerTextWidthFraction = 0.42f,
    coverWidth = 165.dp,
    coverHeight = 240.dp,
    gridMinCellWidth = 155.dp,
    episodeColumns = 10,
    loadingIndicatorSize = 44.dp,
)

val PhoneDimensions = DoplyDimensions(
    screenHorizontalPadding = 16.dp,
    screenVerticalPadding = 12.dp,
    cardWidth = 110.dp,
    cardHeight = 170.dp,
    cardSpacing = 10.dp,
    heroBannerHeight = 200.dp,
    heroBannerTitleSize = 18.sp,
    heroBannerMetaSize = 11.sp,
    heroBannerTextWidthFraction = 0.55f,
    coverWidth = 120.dp,
    coverHeight = 180.dp,
    gridMinCellWidth = 110.dp,
    episodeColumns = 6,
    loadingIndicatorSize = 36.dp,
)

val LocalDimensions = staticCompositionLocalOf { TvDimensions }
val LocalIsTelevision = staticCompositionLocalOf { false }
