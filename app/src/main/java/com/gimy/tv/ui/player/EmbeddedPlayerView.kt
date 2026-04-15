package com.gimy.tv.ui.player

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.gimy.tv.ui.theme.CinemaRed
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun EmbeddedPlayerView(
    player: ExoPlayer?,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onPrevEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
    hasPrevEpisode: Boolean,
    hasNextEpisode: Boolean,
    modifier: Modifier = Modifier,
) {
    var showControls by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }

    // Auto-hide controls after 3 seconds
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(3000)
            showControls = false
        }
    }

    // Update position only when controls are visible
    LaunchedEffect(player, showControls) {
        if (!showControls) return@LaunchedEffect
        while (true) {
            player?.let {
                currentPosition = it.currentPosition
                duration = it.duration.coerceAtLeast(0)
                isPlaying = it.isPlaying
            }
            delay(500)
        }
    }
    // Keep isPlaying in sync for play/pause icon even when controls hidden
    LaunchedEffect(player) {
        while (true) {
            player?.let { isPlaying = it.isPlaying }
            delay(2000)
        }
    }

    Box(modifier = modifier.background(Color.Black)) {
        // Player view
        if (player != null) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        this.player = player
                        useController = false
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Touch gesture overlay
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { showControls = !showControls },
                        onDoubleTap = { offset ->
                            val isLeftSide = offset.x < size.width / 2
                            val seekMs = if (isLeftSide) -10_000L else 10_000L
                            player?.seekTo((player.currentPosition + seekMs).coerceAtLeast(0))
                        },
                    )
                }
        )

        // Controls overlay
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
            ) {
                // Center controls
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onPrevEpisode, enabled = hasPrevEpisode) {
                        Icon(Icons.Default.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    IconButton(onClick = { player?.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }) {
                        Icon(Icons.Default.Replay10, "Rewind", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                    IconButton(onClick = { player?.let { if (it.isPlaying) it.pause() else it.play() } }) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            "Play/Pause",
                            tint = Color.White,
                            modifier = Modifier.size(48.dp),
                        )
                    }
                    IconButton(onClick = { player?.seekTo(player.currentPosition + 10_000) }) {
                        Icon(Icons.Default.Forward10, "Forward", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                    IconButton(onClick = onNextEpisode, enabled = hasNextEpisode) {
                        Icon(Icons.Default.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                }

                // Bottom bar: progress + fullscreen
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Slider(
                        value = if (duration > 0) currentPosition.toFloat() / duration else 0f,
                        onValueChange = { fraction -> player?.seekTo((fraction * duration).toLong()) },
                        colors = SliderDefaults.colors(thumbColor = CinemaRed, activeTrackColor = CinemaRed),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${formatTime(currentPosition)} / ${formatTime(duration)}",
                            color = Color.White,
                            fontSize = 12.sp,
                        )
                        IconButton(onClick = onToggleFullscreen) {
                            Icon(
                                if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                "Fullscreen",
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
