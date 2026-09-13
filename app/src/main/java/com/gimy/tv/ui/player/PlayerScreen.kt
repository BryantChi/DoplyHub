package com.gimy.tv.ui.player

import android.view.KeyEvent
import android.view.ViewGroup
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.*
import com.gimy.tv.ui.theme.*
import com.gimy.tv.ui.theme.LocalIsTelevision
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

@OptIn(ExperimentalTvMaterial3Api::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
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
            // 改用 App 自己的 OkHttp 取串流。預設的 DefaultHttpDataSource 走系統的
            // HttpURLConnection，不吃 App 的 DNS——播放 CDN 被 ISP 的 DNS 過濾時
            // （解析到封鎖頁、自簽憑證），畫面只會全黑而 App 完全無法繞過。
            .setMediaSourceFactory(DefaultMediaSourceFactory(viewModel.mediaDataSourceFactory))
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
    // 重試的等待要在協程裡做；離開播放器時這個 scope 會連同 composition 一起取消。
    val retryScope = rememberCoroutineScope()

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
                // 換線途中不要存：state 的 streamUrl 已經換成新的，但 ExoPlayer 還在播
                // 舊的那條，這時存下去會把舊線路的位置寫進新的一輪。比對兩邊是否指向
                // 同一個來源即可，不必另外拉一個旗標（旗標漏清會變成永遠不再存檔）。
                val playingCurrent = exoPlayer.currentMediaItem
                    ?.localConfiguration?.uri?.toString() == uiState.streamUrl
                if (exoPlayer.isPlaying && playingCurrent) {
                    viewModel.saveProgress(exoPlayer.currentPosition, exoPlayer.duration)
                }
            } catch (_: IllegalStateException) {
                break
            }
        }
    }

    // 待續播的位置，等 STATE_READY 才真的 seek。
    //
    // prepare() 之後 duration 還是 TIME_UNSET，此時 seek 沒辦法判斷會不會超過片長。
    // 換線時尤其危險：新線路的同一集可能比較短（片頭長度、合集切法都不一樣），
    // seek 到片尾就直接觸發 STATE_ENDED，畫面會自己跳下一集，使用者會以為這集被吃掉。
    var pendingResumeMs by remember { mutableLongStateOf(0L) }

    // 快捷選單的狀態。純 UI，不進 ViewModel——process death 後重新叫出來即可。
    var menuState by remember { mutableStateOf(QuickMenuState()) }
    // 每次按鍵都更新，用來重新計算自動隱藏的 8 秒。
    var menuTouchedAt by remember { mutableLongStateOf(0L) }

    // 線路直接用 state 既有的 allSources；集數是當前線路那一組，不必另外存一份。
    val menuSourceGroups = uiState.allSources
    val menuEpisodeList = menuSourceGroups
        .firstOrNull { it.sourceId == uiState.sourceId }
        ?.episodes
        ?: emptyList()
    val menuSources = menuSourceGroups.map {
        QuickMenuItem(label = it.sourceName, isCurrent = it.sourceId == uiState.sourceId)
    }
    val menuEpisodes = menuEpisodeList.map {
        QuickMenuItem(
            label = it.title.ifBlank { "第${it.number}集" },
            isCurrent = it.number == uiState.episodeNum,
        )
    }
    val menuCtx = QuickMenuContext(
        sourceCount = menuSources.size,
        episodeCount = menuEpisodes.size,
        currentSourceIndex = menuSources.indexOfFirst { it.isCurrent }.coerceAtLeast(0),
        currentEpisodeIndex = menuEpisodes.indexOfFirst { it.isCurrent }.coerceAtLeast(0),
    )

    // 8 秒沒動作就收起來。任何按鍵都會更新 menuTouchedAt，key 一變就重新計時。
    LaunchedEffect(menuState.open, menuTouchedAt) {
        if (menuState.open) {
            delay(8000)
            menuState = menuState.copy(open = false)
        }
    }

    // controller 的啟用狀態要跟著選單走。按鍵那條路徑已經設過一次，但自動隱藏
    // 不經過 listener，少了這裡的話選單收起後控制列就再也叫不出來了。
    LaunchedEffect(menuState.open) {
        playerView?.useController = !menuState.open
    }

    // Load media
    LaunchedEffect(uiState.streamUrl) {
        val url = uiState.streamUrl ?: return@LaunchedEffect
        errorRetryCount = 0
        exoPlayer.setMediaItem(MediaItem.fromUri(url))
        exoPlayer.prepare()
        pendingResumeMs = uiState.resumePositionMs
        infoTrigger++
    }

    // Auto next episode + audio error recovery
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && pendingResumeMs > 0) {
                    val target = pendingResumeMs
                    pendingResumeMs = 0L
                    val dur = exoPlayer.duration
                    when {
                        // 直播或還拿不到片長，只能照舊直接 seek。
                        dur <= 0 -> exoPlayer.seekTo(target)
                        // 已經看過九成五就當作看完，從頭播。不這樣處理的話，
                        // STATE_ENDED 存下的 position = duration 會讓每次點進來
                        // 都停在結尾前那幾秒。
                        target >= dur * 0.95 -> Unit
                        // 留 15 秒緩衝：直接 seek 到很接近片尾的位置容易立刻
                        // 觸發 STATE_ENDED，畫面就自己跳下一集了。
                        else -> exoPlayer.seekTo(
                            target.coerceAtMost(dur - 15_000).coerceAtLeast(0)
                        )
                    }
                }
                if (state == Player.STATE_ENDED) {
                    viewModel.saveProgress(exoPlayer.duration, exoPlayer.duration)
                    viewModel.nextEpisode()
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // 4xx 要分辨得出來才能決定值不值得重試——重試一個 404 三次，
                // 得到的還是 404，只是把換線硬生生延後。
                val httpStatus = generateSequence(error.cause as? Throwable) { it.cause }
                    .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()?.responseCode
                when (playbackRetryFor(error.errorCode, httpStatus, errorRetryCount)) {
                    PlaybackRetry.SeekToLiveThenRetry -> {
                        errorRetryCount++
                        exoPlayer.seekToDefaultPosition()
                        exoPlayer.prepare()
                        return
                    }
                    PlaybackRetry.RetryAfterDelay -> {
                        val wait = playbackRetryDelayMs(errorRetryCount)
                        errorRetryCount++
                        // 退到協程去等。listener 不能阻塞，而且退出播放器時 scope 會被取消，
                        // 已排隊的重試不會打到已經 release 的 player。
                        retryScope.launch {
                            delay(wait)
                            runCatching { exoPlayer.prepare() }
                        }
                        return
                    }
                    PlaybackRetry.GiveUp -> Unit
                }
                // 重試無望或已經試夠。原本這裡直接放棄，畫面就永遠停在全黑——使用者不知道發生
                // 什麼事，除錯也只能接 adb 撈 ExoPlayer 的 stack trace（CDN 換根憑證那次就是
                // 這樣才找到的）。改成記一筆 log 並把原因往上報，讓 ViewModel 換線或顯示出來。
                // 一定要連網址一起印：這類失敗幾乎都是「某一個 CDN 有問題」，
                // 而每條線路的 CDN 都不一樣。少了網址就只能從 stack trace 反推是哪一台。
                val failedUri = generateSequence(error.cause as? Throwable) { it.cause }
                    .filterIsInstance<androidx.media3.datasource.HttpDataSource.HttpDataSourceException>()
                    .firstOrNull()?.dataSpec?.uri
                android.util.Log.w(
                    "PlayerFallback",
                    "playback failed (retries=$errorRetryCount): ${error.errorCodeName} http=$httpStatus url=$failedUri",
                    error,
                )
                viewModel.onPlaybackFailed(describePlaybackError(error))
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

    // 退到背景（按 HOME、被別的 App 蓋掉）時暫停並存檔。
    //
    // PlayerScreen 是 NavHost 內的 composable，按 HOME 只會走 Activity 的 onStop，
    // composition 不 dispose，所以上面那個 onDispose 不會觸發。實測按 HOME 之後 12 秒，
    // App 仍持有 audio focus（gain: GAIN、loss: none），背景 10 秒吃掉 5.57 秒 CPU
    // ——影片還在全速解碼，聲音也繼續播。
    //
    // 刻意不做「回到前景自動續播」：使用者可能本來就是按了暫停才切出去的，自動播
    // 反而違反意圖。恢復交給 media3——暫停狀態下 controllerAutoShow 會把控制列叫出來，
    // 按 OK 就能繼續。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.saveProgress(exoPlayer.currentPosition, exoPlayer.duration)
                exoPlayer.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (isTV) {
            // === TV PLAYER ===
            // ── Player ──
            // error == null 是必要的：onPlaybackFailed 只設 error、不清 streamUrl，
            // 少了這個條件 PlayerView 會留在錯誤遮罩底下繼續佔著焦點，
            // 「切換線路重試」按鈕就按不到。
            if (uiState.streamUrl != null && !uiState.isLoading && uiState.error == null) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = true
                            controllerAutoShow = true
                            controllerShowTimeoutMs = 5000
                            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                            // 按鍵是沿著焦點鏈派發的：PlayerView 自己沒有焦點時，
                            // 下面那個 setOnKeyListener 一次都不會被呼叫——快轉與 BACK
                            // 分支等於死碼（實測按 30 次右鍵，播放位置只前進了自然播放的秒數）。
                            // BLOCK_DESCENDANTS 則是不讓 media3 控制列的按鈕把焦點吸走：
                            // 它預設是 FOCUS_AFTER_DESCENDANTS，控制列一顯示焦點就會跑進
                            // 播放鍵，之後左右鍵變成按鈕導航而不是 seek。
                            // 控制列仍然照常顯示，只是純粹當狀態指示，不參與焦點。
                            isFocusable = true
                            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                            // Let PlayerView handle ALL key events natively (D-pad seek, play/pause)
                            setControllerVisibilityListener(
                                PlayerView.ControllerVisibilityListener { vis ->
                                    // When controller shows, also briefly show info
                                    if (vis == android.view.View.VISIBLE) {
                                        infoTrigger++
                                    }
                                }
                            )
                            // Key handling for TV remote:
                            //   BACK             → save progress + exit
                            //   DPAD_LEFT  / MEDIA_REWIND       → seek -10s directly
                            //   DPAD_RIGHT / MEDIA_FAST_FORWARD → seek +10s directly
                            // Default Media3 behavior leaves DPAD_LEFT/RIGHT as
                            // controller-focus navigation, so users had to: show controller
                            // → focus rewind/forward button → press OK to seek. Three steps
                            // for one seek. Directly mapping LEFT/RIGHT to seek matches the
                            // YouTube / Netflix TV experience.
                            setOnKeyListener { _, keyCode, event ->
                                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false

                                // 選單的按鍵優先。只有 reduceMenuKey 回 PassThrough
                                // （＝選單關著且不是開啟鍵）才輪到下面的播放器操作。
                                val menuResult = reduceMenuKey(menuState, keyCode, menuCtx)
                                if (menuResult.action != MenuAction.PassThrough) {
                                    menuState = menuResult.state
                                    menuTouchedAt = System.currentTimeMillis()
                                    // 選單開著時把 media3 控制列整個停用。
                                    // 光呼叫 hideController() 不夠——PlayerView.dispatchKeyEvent
                                    // 處理完按鍵後會再 maybeShowController() 把它叫回來，
                                    // 實測就是控制列與選單疊在一起、底部文字互相重疊。
                                    // 停用還有個附帶好處：useController 為 false 時 media3
                                    // 不再攔截 DPAD，選單內的按鍵一次就進得來。
                                    useController = !menuState.open
                                    when (val a = menuResult.action) {
                                        is MenuAction.PickSource ->
                                            menuSourceGroups.getOrNull(a.index)
                                                ?.let { viewModel.switchSource(it.sourceId) }
                                        is MenuAction.PickEpisode ->
                                            menuEpisodeList.getOrNull(a.index)
                                                ?.let { viewModel.switchEpisode(it.number) }
                                        else -> Unit
                                    }
                                    return@setOnKeyListener true
                                }

                                when (keyCode) {
                                    KeyEvent.KEYCODE_BACK -> {
                                        viewModel.saveProgress(exoPlayer.currentPosition, exoPlayer.duration)
                                        onBack()
                                        true
                                    }
                                    // 控制列的按鈕因為 BLOCK_DESCENDANTS 拿不到焦點，
                                    // 所以暫停得自己接。預設行為只會把控制列叫出來。
                                    KeyEvent.KEYCODE_DPAD_CENTER,
                                    KeyEvent.KEYCODE_ENTER,
                                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                        showController()
                                        true
                                    }
                                    KeyEvent.KEYCODE_DPAD_LEFT,
                                    KeyEvent.KEYCODE_MEDIA_REWIND -> {
                                        val target = (exoPlayer.currentPosition - 10_000).coerceAtLeast(0)
                                        exoPlayer.seekTo(target)
                                        showController()
                                        infoTrigger++
                                        true
                                    }
                                    KeyEvent.KEYCODE_DPAD_RIGHT,
                                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                        val dur = exoPlayer.duration
                                        val raw = exoPlayer.currentPosition + 10_000
                                        val target = if (dur > 0) raw.coerceAtMost(dur) else raw
                                        exoPlayer.seekTo(target)
                                        showController()
                                        infoTrigger++
                                        true
                                    }
                                    else -> false
                                }
                            }
                            playerView = this
                        }
                    },
                    update = { view ->
                        // factory 階段 View 還沒 attach，requestFocus 會失敗；放在 update
                        // 才拿得到。已經有焦點時是 no-op，重組不會互搶。
                        if (!view.hasFocus()) view.requestFocus()
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

            // ── 快捷選單（按上鍵叫出，換集／換線）──
            PlayerQuickMenu(
                state = menuState,
                sources = menuSources,
                episodes = menuEpisodes,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
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

/**
 * 把 ExoPlayer 的例外翻成一句使用者看得懂的話。
 *
 * 為什麼要逐一辨認而不是直接印 message：這些 message 是整串 Java 例外文字（例如
 * 「CertPathValidatorException: Trust anchor for certification path not found」），
 * 對使用者毫無意義。認不出來的就保留 errorCodeName——那是 Media3 定義的字串常數，
 * 混淆後仍然可讀，使用者回報時我們照樣查得到。
 */
private fun describePlaybackError(error: androidx.media3.common.PlaybackException): String {
    val causes = generateSequence(error.cause) { it.cause }
    return when {
        causes.any { it is javax.net.ssl.SSLHandshakeException } -> "連線安全驗證失敗"
        causes.any { it is java.net.UnknownHostException } -> "找不到影片伺服器"
        causes.any { it is java.net.SocketTimeoutException } -> "連線逾時"
        else -> error.errorCodeName
    }
}
