package com.gimy.tv.ui.player

import androidx.media3.common.PlaybackException

/** 播放失敗後該怎麼辦。 */
internal enum class PlaybackRetry {
    /**
     * 直播落後於可用視窗。必須先 seekToDefaultPosition() 再 prepare()——
     * 直接 prepare() 會再失敗一次，因為播放位置還停在已經被伺服器丟棄的片段上。
     */
    SeekToLiveThenRetry,

    /** 暫時性失敗（連線中斷、逾時）。等一下再 prepare()。 */
    RetryAfterDelay,

    /** 重試不會變好，或已經試夠了。把原因報出去，讓上層換線或顯示錯誤。 */
    GiveUp,
}

internal const val MAX_PLAYBACK_RETRIES = 3

/**
 * 依錯誤種類決定重試策略。
 *
 * 原本不分種類一律重試三次。CDN 回 404 的線路重試三次仍然是 404，只是白白拖慢換線；
 * 直播落後視窗更糟，不先 seek 就 prepare() 必定再失敗，三次全部浪費。
 *
 * 4xx 裡 408（請求逾時）與 429（被限流）留給重試——這兩個等一下確實可能好轉；
 * 其餘 4xx 代表「這個網址就是不對」，越早讓上層換線越好。
 *
 * @param httpStatusCode 失敗來自 HTTP 狀態碼時才有值，否則為 null。
 */
internal fun playbackRetryFor(
    errorCode: Int,
    httpStatusCode: Int?,
    attemptsSoFar: Int,
): PlaybackRetry {
    if (attemptsSoFar >= MAX_PLAYBACK_RETRIES) return PlaybackRetry.GiveUp
    if (errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
        return PlaybackRetry.SeekToLiveThenRetry
    }
    if (httpStatusCode != null && httpStatusCode in 400..499 &&
        httpStatusCode != 408 && httpStatusCode != 429
    ) {
        return PlaybackRetry.GiveUp
    }
    return when (errorCode) {
        // 解析與解碼類：重試拿到的是同一份壞掉的內容，或這台裝置本來就不支援這個編碼。
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> PlaybackRetry.GiveUp

        else -> PlaybackRetry.RetryAfterDelay
    }
}

/**
 * 重試前等多久。
 *
 * 網路剛斷掉的瞬間連按三次重試等於沒重試，所以間隔要拉開：0.5 秒、1 秒、2 秒。
 */
internal fun playbackRetryDelayMs(attemptsSoFar: Int): Long =
    500L shl attemptsSoFar.coerceIn(0, MAX_PLAYBACK_RETRIES)
