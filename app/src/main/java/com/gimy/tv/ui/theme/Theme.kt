package com.gimy.tv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api

// ── Cinema Color Palette (existing, unchanged) ──
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

// ── Light Theme Colors (new) ──
val CinemaLightBackground  = Color(0xFFF5F5F5)
val CinemaLightSurface     = Color(0xFFFFFFFF)
val CinemaLightCard        = Color(0xFFFFFFFF)
val CinemaLightElevated    = Color(0xFFFAFAFA)
val CinemaLightBorder      = Color(0xFFE0E0E0)
val CinemaLightTextPrimary = Color(0xFF1A1A1A)
val CinemaLightTextMuted   = Color(0xFF737373)

// ── TV Dark Color Scheme ──
@OptIn(ExperimentalTvMaterial3Api::class)
private val TvDarkColorScheme = androidx.tv.material3.darkColorScheme(
    primary = CinemaRed,
    onPrimary = Color.White,
    primaryContainer = CinemaRedDim,
    secondary = CinemaIndigo,
    background = CinemaBlack,
    onBackground = CinemaTextPrimary,
    surface = CinemaSurface,
    onSurface = CinemaTextPrimary,
    surfaceVariant = CinemaCard,
    onSurfaceVariant = CinemaTextMuted,
    border = CinemaBorder,
)

// ── Mobile Dark Color Scheme ──
private val MobileDarkColorScheme = androidx.compose.material3.darkColorScheme(
    primary = CinemaRed,
    onPrimary = Color.White,
    primaryContainer = CinemaRedDim,
    secondary = CinemaIndigo,
    background = CinemaBlack,
    onBackground = CinemaTextPrimary,
    surface = CinemaSurface,
    onSurface = CinemaTextPrimary,
    surfaceVariant = CinemaCard,
    onSurfaceVariant = CinemaTextMuted,
    outline = CinemaBorder,
)

// ── Mobile Light Color Scheme ──
private val MobileLightColorScheme = androidx.compose.material3.lightColorScheme(
    primary = CinemaRed,
    onPrimary = Color.White,
    primaryContainer = CinemaRedDim,
    secondary = CinemaIndigo,
    background = CinemaLightBackground,
    onBackground = CinemaLightTextPrimary,
    surface = CinemaLightSurface,
    onSurface = CinemaLightTextPrimary,
    surfaceVariant = CinemaLightCard,
    onSurfaceVariant = CinemaLightTextMuted,
    outline = CinemaLightBorder,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GimyTheme(
    isTelevision: Boolean = true,
    darkTheme: Boolean = if (isTelevision) true else isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalIsTelevision provides isTelevision) {
        if (isTelevision) {
            androidx.tv.material3.MaterialTheme(
                colorScheme = TvDarkColorScheme,
                content = content
            )
        } else {
            val colorScheme = if (darkTheme) MobileDarkColorScheme else MobileLightColorScheme
            androidx.compose.material3.MaterialTheme(
                colorScheme = colorScheme,
                content = content
            )
        }
    }
}

// Backward-compatible alias — existing code calling GimyTVTheme still works
@Composable
fun GimyTVTheme(content: @Composable () -> Unit) = GimyTheme(isTelevision = true, content = content)
