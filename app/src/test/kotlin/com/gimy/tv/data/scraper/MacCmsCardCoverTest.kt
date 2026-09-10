package com.gimy.tv.data.scraper

import com.google.common.truth.Truth.assertThat
import org.jsoup.Jsoup
import org.junit.Test

/**
 * MacCms list cards normally carry the poster in data-original (lazy-load), but a minority
 * inline it as a CSS background instead — 10 of 154 cards on 123kubo's drama listing, which
 * showed up as blank tiles. Covering that third form is what this pins down.
 */
class MacCmsCardCoverTest {

    private fun card(attrs: String) = Jsoup.parse("<a $attrs></a>").selectFirst("a")!!

    @Test fun `prefers data-original`() {
        val c = card("""data-original="https://cdn/a.jpg" data-src="https://cdn/b.jpg"""")
        assertThat(macCmsCardCover(c)).isEqualTo("https://cdn/a.jpg")
    }

    @Test fun `falls back to data-src`() {
        assertThat(macCmsCardCover(card("""data-src="https://cdn/b.jpg""""))).isEqualTo("https://cdn/b.jpg")
    }

    /** The 123kubo case: no lazy-load attribute at all, poster lives in the style rule. */
    @Test fun `falls back to a css background url`() {
        val c = card("""style="background: url(https://img.avdb.me/x/y.jpg) center/cover"""")
        assertThat(macCmsCardCover(c)).isEqualTo("https://img.avdb.me/x/y.jpg")
    }

    @Test fun `handles quotes inside the css url`() {
        val c = card("""style="background-image:url('https://img.avdb.me/x/y.jpg')"""")
        assertThat(macCmsCardCover(c)).isEqualTo("https://img.avdb.me/x/y.jpg")
    }

    @Test fun `handles double quotes inside the css url`() {
        val c = card("""style='background:url("https://img.avdb.me/x/y.jpg")'""")
        assertThat(macCmsCardCover(c)).isEqualTo("https://img.avdb.me/x/y.jpg")
    }

    @Test fun `a style without a url yields empty`() {
        assertThat(macCmsCardCover(card("""style="color:red""""))).isEmpty()
    }

    @Test fun `no source at all yields empty`() {
        assertThat(macCmsCardCover(card("""class="myui-vodlist__thumb""""))).isEmpty()
    }
}
