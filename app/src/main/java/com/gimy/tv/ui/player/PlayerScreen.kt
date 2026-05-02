package com.gimy.tv.ui.player

import android.view.KeyEvent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import com.gimy.tv.ui.components.DoplyButton
import com.gimy.tv.ui.components.DoplyLoadingIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.ui.PlayerView
import androidx.tv.material3.*
import com.gimy.tv.ui.theme.*
import com.gimy.tv.ui.theme.LocalIsTelevision
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val isTV = LocalIsTelevision.current

    // Keep screen on during playback — prevent sleep/screensaver
    val activity = context as? android.app.Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Optimized ExoPlayer with better audio and buffering
    val exoPlayer = remember {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000,   // minBufferMs
                120_000,  // maxBufferMs
                2_500,    // bufferForPlaybackMs
                5_000     // bufferForPlaybackAfterRebufferMs
            )
            .build()

        ExoPlayer.Builder(context.applicationContext)
            .setLoadControl(loadControl)
            .build().apply {
                playWhenReady = true
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true
                )
                volume = 1.0f
                // Prevent audio drops by keeping audio renderer active
                pauseAtEndOfMediaItems = false
            }
    }

    // Boost audio output by +15 dB via LoudnessEnhancer
    val loudnessEnhancer = remember(exoPlayer) {
        try {
            LoudnessEnhancer(exoPlayer.audioSessionId).apply {
                setTargetGain(1800) // +18 dB in millibels
                enabled = true
            }
        } catch (_: Exception) { null }
    }

    // PlayerView reference for showing/hiding controller
    var playerView by remember { mutableStateOf<PlayerView?>(null) }

    // Error retry limiter — prevent infinite prepare() loop
    var errorRetryCount by remember { mutableIntStateOf(0) }

    // Info bar visibility with auto-hide counter
    var infoVisible by remember { mutableStateOf(true) }
    var infoTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(infoTrigger) {
        infoVisible = true
        delay(3500)
        infoVisible = false
    }

    // Auto-save progress
    LaunchedEffect(exoPlayer) {
        while (isActive) {
            delay(10_000)
            try {
                if (exoPlayer.isPlaying) {
                    viewModel.saveProgress(exoPlayer.currentPosition, exoPlayer.duration)
                }
            } catch (_: IllegalStateException) {
                break
            }
        }
    }

    // Load media
    LaunchedEffect(uiState.streamUrl) {
        val url = uiState.streamUrl ?: return@LaunchedEffect
        errorRetryCount = 0
        exoPlayer.setMediaItem(MediaItem.fromUri(url))
        exoPlayer.prepare()
        if (uiState.resumePositionMs > 0) {
            exoPlayer.seekTo(uiState.resumePositionMs)
        }
        infoTrigger++
    }

    // Auto next episode + audio error recovery
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    viewModel.saveProgress(exoPlayer.duration, exoPlayer.duration)
                    viewModel.nextEpisode()
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (errorRetryCount < 3) {
                    errorRetryCount++
                    exoPlayer.prepare()
                }
                // After 3 retries, stop — avoid infinite loop on non-recoverable errors
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            viewModel.saveProgress(exoPlayer.currentPosition, exoPlayer.duration)
            exoPlayer.removeListener(listener)
            loudnessEnhancer?.release()
            exoPlayer.release()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (isTV) {
            // === TV PLAYER ===
            // ── Player ──
            if (uiState.streamUrl != null && !uiState.isLoading) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = true
                            controllerAutoShow = true
                            controllerShowTimeoutMs = 5000
                            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                            // Let PlayerView handle ALL key events natively (D-pad seek, play/pause)
                            setControllerVisibilityListener(
                                PlayerView.ControllerVisibilityListener { vis ->
                                    // When controller shows, also briefly show info
                                    if (vis == android.view.View.VISIBLE) {
                                        infoTrigger++
                                    }
                                }
                            )
                            // Intercept only BACK key
                            setOnKeyListener { _, keyCode, event ->
                                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
                                    viewModel.saveProgress(exoPlayer.currentPosition, exoPlayer.duration)
                                    onBack()
                                    true
                                } else {
                                    false // Let PlayerView handle everything else
                                }
                            }
                            playerView = this
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // ── Info bar (auto-hide 3.5s) ──
            AnimatedVisibility(
                visible = infoVisible && uiState.streamUrl != null && !uiState.isLoading && uiState.error == null,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                Box(
                    Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 24.dp, vertical = 10.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Column {
                            Text(uiState.vodTitle, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(uiState.episodeTitle, color = CinemaTextMuted, fontSize = 12.sp)
                        }
                        Surface(
                            colors = SurfaceDefaults.colors(containerColor = CinemaRed.copy(0.9f)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(uiState.sourceName, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                    }
                }
            }
        } else {
            // === MOBILE PLAYER ===
            if (uiState.streamUrl != null && !uiState.isLoading) {
                EmbeddedPlayerView(
                    player = exoPlayer,
                    isFullscreen = true,
                    onToggleFullscreen = { onBack() },
                    onPrevEpisode = {
                        if (uiState.episodeNum > 1) viewModel.switchEpisode(uiState.episodeNum - 1)
                    },
                    onNextEpisode = {
                        if (uiState.episodeNum < uiState.totalEpisodes) viewModel.switchEpisode(uiState.episodeNum + 1)
                    },
                    hasPrevEpisode = uiState.episodeNum > 1,
                    hasNextEpisode = uiState.episodeNum < uiState.totalEpisodes,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ── Loading ──
        AnimatedVisibility(uiState.isLoading, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    DoplyLoadingIndicator(48.dp)
                    Spacer(Modifier.height(20.dp))
                    Text(uiState.vodTitle.ifBlank { "載入中" }, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(uiState.loadingMessage, color = CinemaTextMuted, fontSize = 14.sp)
                    if (uiState.sourceName.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text("線路：${uiState.sourceName}", color = CinemaRed, fontSize = 13.sp)
                    }
                }
            }
        }

        // ── Error ──
        if (uiState.error != null && !uiState.isLoading) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(uiState.error ?: "", color = CinemaRed, fontSize = 15.sp)
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DoplyButton(onClick = onBack, containerColor = CinemaSurface) { Text("返回", color = Color.White) }
                        if (uiState.allSources.size > 1) {
                            DoplyButton(onClick = { viewModel.retryWithNextSource() },
                                containerColor = CinemaRed
                            ) { Text("切換線路重試", color = Color.White) }
                        }
                    }
                }
            }
        }
    }
}
