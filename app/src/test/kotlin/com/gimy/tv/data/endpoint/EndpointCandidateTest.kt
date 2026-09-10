package com.gimy.tv.data.endpoint

import com.gimy.tv.data.scraper.parser.GimyLayout
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * endpoints.json used to be a plain list of URLs, which assumed every candidate for a source
 * shared one path layout. The Gimy mirrors break that: gimytv.me, gimyai.tw and gitube.tv
 * serve the same catalogue under three different path/template combinations, so a candidate
 * has to carry which one it is — otherwise switching mirrors silently parses nothing.
 */
class EndpointCandidateTest {

    @Test fun `a bare url keeps the source default profile`() {
        val c = endpointCandidate("https://gimytv.me", null)
        assertThat(c).isNotNull()
        assertThat(c!!.url).isEqualTo("https://gimytv.me")
        assertThat(c.profile).isNull()
    }

    @Test fun `a url with a profile keeps both`() {
        val c = endpointCandidate("https://gimyai.tw", "poster")
        assertThat(c!!.profile).isEqualTo("poster")
    }

    @Test fun `surrounding whitespace is trimmed`() {
        val c = endpointCandidate("  https://gimytv.me  ", "  card  ")
        assertThat(c!!.url).isEqualTo("https://gimytv.me")
        assertThat(c.profile).isEqualTo("card")
    }

    @Test fun `a blank url is rejected`() {
        assertThat(endpointCandidate("   ", "card")).isNull()
        assertThat(endpointCandidate(null, "card")).isNull()
    }

    @Test fun `a blank profile degrades to the default rather than an empty name`() {
        assertThat(endpointCandidate("https://gimytv.me", "  ")!!.profile).isNull()
    }

    // ── profile → mirror settings ──

    @Test fun `card profile maps to gimytv paths`() {
        val m = gimyMirrorFor("card")
        assertThat(m.paths.list).isEqualTo("/type")
        assertThat(m.paths.detail).isEqualTo("/vod")
        assertThat(m.paths.episode).isEqualTo("/ep")
        assertThat(m.layout).isEqualTo(GimyLayout.CARD)
    }

    @Test fun `poster profile maps to gimyai paths and template`() {
        val m = gimyMirrorFor("poster")
        assertThat(m.paths.list).isEqualTo("/genre")
        assertThat(m.paths.detail).isEqualTo("/detail")
        assertThat(m.paths.episode).isEqualTo("/play")
        assertThat(m.layout).isEqualTo(GimyLayout.POSTER)
    }

    /** gitube.tv shares the card template but under entirely different path tokens. */
    @Test fun `browse profile is card template on different paths`() {
        val m = gimyMirrorFor("browse")
        assertThat(m.paths.list).isEqualTo("/browse")
        assertThat(m.paths.detail).isEqualTo("/title")
        assertThat(m.paths.episode).isEqualTo("/watch")
        assertThat(m.layout).isEqualTo(GimyLayout.CARD)
    }

    /** An unknown name must not brick the source — fall back to the classic layout. */
    @Test fun `an unknown or absent profile falls back to card`() {
        assertThat(gimyMirrorFor("something-new").layout).isEqualTo(GimyLayout.CARD)
        assertThat(gimyMirrorFor(null).paths.detail).isEqualTo("/vod")
    }

    @Test fun `profile matching ignores case`() {
        assertThat(gimyMirrorFor("POSTER").layout).isEqualTo(GimyLayout.POSTER)
    }

}
