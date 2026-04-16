package com.gimy.tv.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gimy.tv.ui.theme.CinemaRed

// ═══════════════════════════════════════
// Gesture mode state
// ═══════════════════════════════════════

sealed interface GestureMode {
    data object Idle : GestureMode
    data class Seeking(val deltaMs: Long) : GestureMode
    data class Volume(val fraction: Float) : GestureMode
    data class Brightness(val fraction: Float) : GestureMode
    data class Speed(val rate: Float) : GestureMode
}

// ═══════════════════════════════════════
// Overlay composables
// ═══════════════════════════════════════

private val overlayBackground = Color.Black.copy(alpha = 0.65f)
private val overlayShape = RoundedCornerShape(12.dp)

@Composable
fun SeekOverlay(gestureMode: GestureMode) {
    AnimatedVisibility(
        visible = gestureMode is GestureMode.Seeking,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        val deltaMs = (gestureMode as? GestureMode.Seeking)?.deltaMs ?: 0L
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Row(
                Modifier
                    .background(overlayBackground, overlayShape)
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    if (deltaMs >= 0) Icons.Default.FastForward else Icons.Default.FastRewind,
                    contentDescription = null,
                    tint = CinemaRed,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    text = formatSeekDelta(deltaMs),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
fun VolumeOverlay(gestureMode: GestureMode) {
    AnimatedVisibility(
        visible = gestureMode is GestureMode.Volume,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        val fraction = (gestureMode as? GestureMode.Volume)?.fraction ?: 0f
        val icon = when {
            fraction <= 0f -> Icons.Default.VolumeOff
            fraction < 0.5f -> Icons.Default.VolumeDown
            else -> Icons.Default.VolumeUp
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            VerticalIndicator(
                icon = icon,
                fraction = fraction,
                modifier = Modifier.padding(end = 32.dp),
            )
        }
    }
}

@Composable
fun BrightnessOverlay(gestureMode: GestureMode) {
    AnimatedVisibility(
        visible = gestureMode is GestureMode.Brightness,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        val fraction = (gestureMode as? GestureMode.Brightness)?.fraction ?: 0f
        val icon = when {
            fraction < 0.33f -> Icons.Default.BrightnessLow
            fraction < 0.66f -> Icons.Default.BrightnessMedium
            else -> Icons.Default.BrightnessHigh
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            VerticalIndicator(
                icon = icon,
                fraction = fraction,
                modifier = Modifier.padding(start = 32.dp),
            )
        }
    }
}

@Composable
fun SpeedOverlay(gestureMode: GestureMode) {
    AnimatedVisibility(
        visible = gestureMode is GestureMode.Speed,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        val rate = (gestureMode as? GestureMode.Speed)?.rate ?: 2f
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .background(overlayBackground, overlayShape)
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.FastForward,
                        contentDescription = null,
                        tint = CinemaRed,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = "${rate}x",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = "↕ 滑動調整",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

val SPEED_STEPS = floatArrayOf(1.5f, 2f, 3f, 4f, 5f)

// ═══════════════════════════════════════
// Shared components
// ═══════════════════════════════════════

@Composable
private fun VerticalIndicator(
    icon: ImageVector,
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(overlayBackground, overlayShape)
            .padding(horizontal = 14.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = CinemaRed, modifier = Modifier.size(24.dp))
        Box(
            Modifier
                .width(4.dp)
                .height(120.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.3f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(fraction.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(2.dp))
                    .background(CinemaRed)
            )
        }
        Text(
            text = "${(fraction * 100).toInt()}%",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ═══════════════════════════════════════
// Helpers
// ═══════════════════════════════════════

private fun formatSeekDelta(deltaMs: Long): String {
    val absSec = kotlin.math.abs(deltaMs) / 1000
    val minutes = absSec / 60
    val seconds = absSec % 60
    val sign = if (deltaMs >= 0) "+" else "-"
    return "$sign${minutes}:%02d".format(seconds)
}
