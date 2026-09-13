package com.gimy.tv.ui.player

import androidx.media3.common.PlaybackException
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 重試策略決定使用者在一條壞掉的線路上要多等幾秒才換到下一條。
 *
 * 原本不分種類一律重試三次，兩種情況特別虧：CDN 回 404 重試三次還是 404；
 * 直播落後視窗時不先 seek 就 prepare() 必定再失敗，三次全浪費。
 */
class PlaybackRetryTest {

    @Test
    fun `直播落後視窗要先 seek 再 prepare`() {
        assertThat(
            playbackRetryFor(
                errorCode = PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
                httpStatusCode = null,
                attemptsSoFar = 0,
            )
        ).isEqualTo(PlaybackRetry.SeekToLiveThenRetry)
    }

    @Test
    fun `連線失敗屬於暫時性，值得重試`() {
        assertThat(
            playbackRetryFor(
                errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                httpStatusCode = null,
                attemptsSoFar = 0,
            )
        ).isEqualTo(PlaybackRetry.RetryAfterDelay)
    }

    @Test
    fun `一般 4xx 直接放棄，讓上層換線`() {
        listOf(400, 403, 404, 410).forEach { status ->
            assertThat(
                playbackRetryFor(
                    errorCode = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                    httpStatusCode = status,
                    attemptsSoFar = 0,
                )
            ).isEqualTo(PlaybackRetry.GiveUp)
        }
    }

    /** 這兩個 4xx 講的是「現在不行」而不是「這個網址不對」，等一下確實可能好轉。 */
    @Test
    fun `逾時與限流仍然重試`() {
        listOf(408, 429).forEach { status ->
            assertThat(
                playbackRetryFor(
                    errorCode = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                    httpStatusCode = status,
                    attemptsSoFar = 0,
                )
            ).isEqualTo(PlaybackRetry.RetryAfterDelay)
        }
    }

    @Test
    fun `解碼與解析失敗重試不會變好`() {
        listOf(
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        ).forEach { code ->
            assertThat(playbackRetryFor(code, httpStatusCode = null, attemptsSoFar = 0))
                .isEqualTo(PlaybackRetry.GiveUp)
        }
    }

    @Test
    fun `試滿次數後不再重試`() {
        assertThat(
            playbackRetryFor(
                errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                httpStatusCode = null,
                attemptsSoFar = MAX_PLAYBACK_RETRIES,
            )
        ).isEqualTo(PlaybackRetry.GiveUp)
    }

    /** 網路剛斷掉的瞬間連按三次重試等於沒重試，間隔必須拉開。 */
    @Test
    fun `等待間隔會隨著失敗次數拉長`() {
        val waits = (0 until MAX_PLAYBACK_RETRIES).map { playbackRetryDelayMs(it) }
        assertThat(waits).isEqualTo(listOf(500L, 1000L, 2000L))
        assertThat(waits).isInOrder()
    }
}
