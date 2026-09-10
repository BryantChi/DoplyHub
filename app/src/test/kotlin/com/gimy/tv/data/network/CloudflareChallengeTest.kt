package com.gimy.tv.data.network

import com.gimy.tv.domain.model.SourceType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CloudflareChallengeTest {

    // ── challenge detection ──

    /**
     * Body markers taken from the real 403 pages captured 2026-09-10 on gimytv.me,
     * gitube.tv and jable.tv.
     */
    private val challengeBody = """
        <html><head><title>Just a moment...</title></head><body>
        <script>(function(){window._cf_chl_opt={cvId:'3',cZone:'gimytv.me'};})();</script>
        </body></html>
    """.trimIndent()

    @Test fun `403 carrying a challenge marker is a challenge`() {
        assertThat(isCloudflareChallenge(403, challengeBody)).isTrue()
    }

    /**
     * A site's own permission error is also a 403. Treating every 403 as a challenge
     * would spin up a WebView for something no amount of solving can fix.
     */
    @Test fun `403 without a challenge marker is not a challenge`() {
        assertThat(isCloudflareChallenge(403, "<html><body>Forbidden</body></html>")).isFalse()
    }

    /** A normal page merely mentioning the marker must not trigger a solve. */
    @Test fun `200 carrying a challenge marker is not a challenge`() {
        assertThat(isCloudflareChallenge(200, challengeBody)).isFalse()
    }

    @Test fun `503 challenge is also recognised`() {
        assertThat(isCloudflareChallenge(503, challengeBody)).isTrue()
    }

    // ── user agent selection ──

    private val webViewUa =
        "Mozilla/5.0 (Linux; Android 14; sdk_google_atv64_arm64 Build/UE1A) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/126.0.6478.71 Safari/537.36"

    /** cf_clearance is bound to the UA that solved the challenge, so Jable must send
     *  exactly what the WebView sent. */
    @Test fun `jable uses the webview user agent when one is available`() {
        assertThat(embedUserAgent(SourceType.JABLE_TV, webViewUa)).isEqualTo(webViewUa)
    }

    /**
     * XNXX shares EmbeddedHlsSource with Jable but is not behind Cloudflare. Changing its
     * UA buys nothing and risks a working source — this test exists to catch that regression.
     */
    @Test fun `xnxx keeps its original user agent`() {
        assertThat(embedUserAgent(SourceType.XNXX, webViewUa)).isEqualTo(DEFAULT_EMBED_USER_AGENT)
    }

    @Test fun `jable falls back to the embed default when webview is unavailable`() {
        assertThat(embedUserAgent(SourceType.JABLE_TV, null)).isEqualTo(DEFAULT_EMBED_USER_AGENT)
    }

    @Test fun `shared http client prefers the webview user agent`() {
        assertThat(httpUserAgent(webViewUa)).isEqualTo(webViewUa)
    }

    @Test fun `shared http client falls back when webview is unavailable`() {
        assertThat(httpUserAgent(null)).isEqualTo(FALLBACK_HTTP_USER_AGENT)
    }
}
