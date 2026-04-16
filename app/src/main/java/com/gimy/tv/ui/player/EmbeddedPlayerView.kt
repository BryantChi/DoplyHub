package com.gimy.tv.ui.player

import android.app.Activity
import android.media.AudioManager
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.gimy.tv.ui.theme.CinemaRed
import kotlinx.coroutines.delay
import kotlin.math.absoluteValue

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

    // Gesture state
    var gestureMode by remember { mutableStateOf<GestureMode>(GestureMode.Idle) }
    // Long-press coordination: LaunchedEffect watches these to fire after 500ms
    var pointerIsDown by remember { mutableStateOf(false) }
    var pointerDownToken by remember { mutableLongStateOf(0L) }
    var gestureDragLocked by remember { mutableStateOf(false) }

    // System services
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager }
    val activity = remember { context as? Activity }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    val density = LocalDensity.current.density

    // Long-press detection via LaunchedEffect (outside restricted AwaitPointerEventScope)
    LaunchedEffect(pointerDownToken) {
        if (!pointerIsDown || pointerDownToken == 0L) return@LaunchedEffect
        delay(500)
        if (pointerIsDown && !gestureDragLocked && gestureMode is GestureMode.Idle) {
            val initialRate = SPEED_STEPS[0] // 1.5x
            player?.playbackParameters = PlaybackParameters(initialRate)
            gestureMode = GestureMode.Speed(initialRate)
        }
    }

    // Auto-hide controls after 3 seconds (paused during active gesture)
    LaunchedEffect(showControls, gestureMode) {
        if (showControls && gestureMode is GestureMode.Idle) {
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
        // 1. Player view
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
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

        // 2. Touch gesture overlay (invisible full-screen touch catcher)
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(player) {
                    val touchSlopPx = 10.dp.toPx()
                    val seekMsPerDp = 150f
                    val doubleTapTimeoutMs = 300L

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startX = down.position.x
                        val startY = down.position.y
                        var locked: String? = null // "seek", "volume", "brightness"
                        var isTap = true
                        var seekDelta = 0L

                        // Snapshot audio/brightness at touch down
                        val volAtDown = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val brightAtDown = activity?.window?.attributes?.screenBrightness
                            ?.takeIf { it >= 0f } ?: 0.5f

                        // Signal long-press LaunchedEffect
                        gestureDragLocked = false
                        pointerIsDown = true
                        pointerDownToken++

                        // Move loop
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull() ?: break

                            if (change.changedToUp()) {
                                change.consume()
                                break
                            }

                            val dx = change.position.x - startX
                            val dy = change.position.y - startY

                            // Check if LaunchedEffect triggered long-press
                            val longPressFired = gestureMode is GestureMode.Speed

                            // Determine direction once past touch slop
                            if (locked == null && !longPressFired &&
                                (dx.absoluteValue > touchSlopPx || dy.absoluteValue > touchSlopPx)
                            ) {
                                isTap = false
                                gestureDragLocked = true // cancel pending long-press
                                locked = if (dx.absoluteValue > dy.absoluteValue) {
                                    "seek"
                                } else if (startX > size.width / 2) {
                                    "volume"
                                } else {
                                    "brightness"
                                }
                                showControls = false
                            }

                            // If long-press fired, mark as non-tap
                            if (longPressFired) {
                                isTap = false
                            }

                            // Update gesture based on locked mode
                            when {
                                locked == "seek" -> {
                                    val dragDp = dx / density
                                    seekDelta = (dragDp * seekMsPerDp).toLong()
                                    gestureMode = GestureMode.Seeking(seekDelta)
                                }
                                locked == "volume" -> {
                                    val dragDp = (startY - change.position.y) / density
                                    val volumeDelta = (dragDp / 200f * maxVolume).toInt()
                                    val newVol = (volAtDown + volumeDelta).coerceIn(0, maxVolume)
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                                    gestureMode = GestureMode.Volume(newVol.toFloat() / maxVolume)
                                }
                                locked == "brightness" -> {
                                    val dragDp = (startY - change.position.y) / density
                                    val brightDelta = dragDp / 300f
                                    val newBright = (brightAtDown + brightDelta).coerceIn(0.01f, 1f)
                                    activity?.window?.let { w ->
                                        val attrs = w.attributes
                                        attrs.screenBrightness = newBright
                                        w.attributes = attrs
                                    }
                                    gestureMode = GestureMode.Brightness(newBright)
                                }
                                // Long-press active: vertical drag adjusts speed
                                longPressFired && dy.absoluteValue > touchSlopPx -> {
                                    val dragDp = (startY - change.position.y) / density
                                    // Map vertical drag to speed steps: ~60dp per step
                                    val stepOffset = (dragDp / 60f).toInt()
                                    val baseIdx = SPEED_STEPS.indexOfFirst { it == 1.5f }.coerceAtLeast(0)
                                    val idx = (baseIdx + stepOffset).coerceIn(0, SPEED_STEPS.lastIndex)
                                    val newRate = SPEED_STEPS[idx]
                                    player?.playbackParameters = PlaybackParameters(newRate)
                                    gestureMode = GestureMode.Speed(newRate)
                                }
                            }

                            if (locked != null || longPressFired) {
                                change.consume()
                            }
                        }

                        // Finger up
                        pointerIsDown = false

                        // Apply seek on lift
                        if (locked == "seek" && player != null) {
                            player.seekTo((player.currentPosition + seekDelta).coerceAtLeast(0))
                        }

                        // Restore speed on lift
                        if (gestureMode is GestureMode.Speed) {
                            player?.playbackParameters = PlaybackParameters(1f)
                        }

                        gestureMode = GestureMode.Idle

                        // Handle tap / double-tap
                        if (isTap) {
                            // Wait for possible second tap (double-tap detection)
                            val secondDown = withTimeoutOrNull(doubleTapTimeoutMs) {
                                awaitFirstDown(requireUnconsumed = false)
                            }
                            if (secondDown != null) {
                                // Double-tap: seek ±10s
                                val isLeftSide = secondDown.position.x < size.width / 2
                                val seekMs = if (isLeftSide) -10_000L else 10_000L
                                player?.seekTo(
                                    (player.currentPosition + seekMs).coerceAtLeast(0)
                                )
                                // Wait for finger up from second tap
                                while (true) {
                                    val ev = awaitPointerEvent(PointerEventPass.Main)
                                    val c = ev.changes.firstOrNull() ?: break
                                    if (c.changedToUp()) {
                                        c.consume()
                                        break
                                    }
                                }
                            } else {
                                // Single tap: toggle controls
                                showControls = !showControls
                            }
                        }
                    }
                }
        )

        // 3. Controls overlay (existing)
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

        // 4. Gesture feedback overlays (on top of everything)
        SeekOverlay(gestureMode)
        VolumeOverlay(gestureMode)
        BrightnessOverlay(gestureMode)
        SpeedOverlay(gestureMode)
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
