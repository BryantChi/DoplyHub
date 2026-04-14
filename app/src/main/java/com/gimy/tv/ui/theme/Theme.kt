package com.gimy.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

// ── Cinematic Dark palette (OTT/Streaming) ──
val CinemaBlack       = Color(0xFF000000)
val CinemaBase        = Color(0xFF0A0A14)
val CinemaElevated    = Color(0xFF0F0F23)
val CinemaCard        = Color(0xFF131320)
val CinemaSurface     = Color(0xFF1A1A2E)
val CinemaBorder      = Color(0xFF252545)
val CinemaRed         = Color(0xFFE11D48)   // Primary accent — play/action
val CinemaRedDim      = Color(0xFF9F1239)
val CinemaIndigo      = Color(0xFF4338CA)   // Secondary
val CinemaTextPrimary = Color(0xFFF0F0F5)
val CinemaTextMuted   = Color(0xFF8088A4)
val CinemaGold        = Color(0xFFFBBF24)   // Rating/highlight

@OptIn(ExperimentalTvMaterial3Api::class)
private val CinemaDarkScheme = darkColorScheme(
    primary = CinemaRed,
    onPrimary = Color.White,
    primaryContainer = CinemaRedDim,
    onPrimaryContainer = Color.White,
    secondary = CinemaIndigo,
    onSecondary = Color.White,
    background = CinemaBlack,
    onBackground = CinemaTextPrimary,
    surface = CinemaCard,
    onSurface = CinemaTextPrimary,
    surfaceVariant = CinemaSurface,
    onSurfaceVariant = CinemaTextMuted,
    error = Color(0xFFEF4444),
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GimyTVTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CinemaDarkScheme, content = content)
}
