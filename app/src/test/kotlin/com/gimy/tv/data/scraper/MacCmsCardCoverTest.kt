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

    /** 2026-10 gimy.tw 與 momovod 列表改版：錨點上沒有任何 lazy-load 屬性，封面改由內層 img 直接載入。 */
    @Test fun `falls back to the inner img src`() {
        val c = Jsoup.parse(
            """<a class="myui-vodlist__thumb site-cover" href="/vod/529906.html" title="富閨可期"><img class="site-cover-img" src="https://img.azgo.me/x/8c61.jpg" alt="富閨可期"><span class="pic-text">更新至19集</span></a>"""
        ).selectFirst("a")!!
        assertThat(macCmsCardCover(c)).isEqualTo("https://img.azgo.me/x/8c61.jpg")
    }

    @Test fun `no source at all yields empty`() {
        assertThat(macCmsCardCover(card("""class="myui-vodlist__thumb""""))).isEmpty()
    }
}
