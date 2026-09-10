package com.gimy.tv.data.scraper

import com.gimy.tv.data.scraper.parser.GimyLayout
import com.gimy.tv.data.scraper.parser.GimyParser
import com.gimy.tv.data.scraper.parser.GimyPaths
import com.gimy.tv.domain.model.SourceType
import com.google.common.truth.Truth.assertThat
import org.jsoup.Jsoup
import org.junit.Test

/**
 * gimyai.tw mirrors gimytv.me's catalogue (same ids, verified by opening the same id on both
 * and getting the same title) but ships a different template: a.poster cards instead of
 * a.card__thumb, and route blocks keyed by a data-route-sid attribute instead of encoding
 * the source id in the episode URL.
 *
 * Fixtures trimmed from real pages fetched 2026-09-10.
 */
class GimyPosterLayoutTest {

    private val posterParser = GimyParser(
        SourceType.GIMYTV,
        GimyPaths(list = "/genre", detail = "/detail", episode = "/play"),
        layout = GimyLayout.POSTER,
    )
    private val base = "https://gimyai.tw"

    private val listHtml = """
        <div class="grid">
          <a class="poster" href="/detail/483139.html">
            <span class="poster__thumb">
              <img src="https://imgs.1777cdn.com/upload/vod/a.jpg" alt="早春晴朗">
              <span class="poster__status">24集全</span>
            </span>
            <h3 class="poster__title">早春晴朗</h3>
          </a>
          <a class="poster" href="/detail/100.html">
            <span class="poster__thumb">
              <img src="/upload/vod/b.jpg" alt="電影X">
              <span class="poster__status">HD</span>
            </span>
            <h3 class="poster__title">電影X</h3>
          </a>
        </div>
    """.trimIndent()

    @Suppress("DEPRECATION")
    @Test fun `parses poster cards`() {
        val r = posterParser.parseVodList(Jsoup.parse(listHtml, base), base, page = 1)
        assertThat(r.items).hasSize(2)
        assertThat(r.items[0].id).isEqualTo(483139L)
        assertThat(r.items[0].title).isEqualTo("早春晴朗")
        assertThat(r.items[0].status).isEqualTo("24集全")
        assertThat(r.items[1].coverUrl).isEqualTo("https://gimyai.tw/upload/vod/b.jpg")
    }

    /**
     * Real structure: every route lives inside ONE div.block, each as a .route-title followed
     * by its .eps sibling. An earlier fixture gave each route its own block — it passed while
     * the real page yielded a single route with all 93 episodes merged into it.
     */
    private val detailHtml = """
        <html><head><meta property="og:image" content="https://cdn/c.jpg"></head><body>
          <h1>那些你不知道的我</h1>
          <p>導演： 王五 主演： 甲,乙 年代： 2026 類型： 韓劇 狀態： 更新至12集</p>
          <p>這是一段夠長的劇情介紹文字用來通過長度門檻超過五十個字所以多寫一些內容填充長度。</p>
          <div class="block">
            <div class="route-title">藍光線路 ᴴᴰ<span class="hd">HD</span></div>
            <div class="eps episodes-route is-open" data-route-sid="12">
              <a class="ep" href="/play/425737-12-1.html">第01集</a>
              <a class="ep" href="/play/425737-12-2.html">第02集</a>
            </div>
            <div class="route-title">無盡雲</div>
            <div class="eps episodes-route" data-route-sid="3">
              <a class="ep" href="/play/425737-3-1.html">第01集</a>
            </div>
          </div>
        </body></html>
    """.trimIndent()

    @Test fun `parses poster detail routes keyed by data-route-sid`() {
        val d = posterParser.parseVodDetail(Jsoup.parse(detailHtml, base), 425737L, base)
        assertThat(d.vod.title).isEqualTo("那些你不知道的我")
        assertThat(d.episodes).hasSize(2)
        val bluray = d.episodes.first { it.sourceName.startsWith("藍光線路") }
        assertThat(bluray.sourceId).isEqualTo(12)
        assertThat(bluray.episodes).hasSize(2)
        assertThat(bluray.episodes[0].playUrl).isEqualTo("/play/425737-12-1.html")
        assertThat(d.episodes.first { it.sourceName == "無盡雲" }.sourceId).isEqualTo(3)
    }

    /** A card-layout parser must not silently half-read a poster page. */
    @Test fun `card layout yields nothing on a poster page`() {
        val cardParser = GimyParser(
            SourceType.GIMYTV,
            GimyPaths(list = "/type", detail = "/vod", episode = "/ep"),
            layout = GimyLayout.CARD,
        )
        assertThat(cardParser.parseVodList(Jsoup.parse(listHtml, base), base, 1).items).isEmpty()
        assertThat(cardParser.parseVodDetail(Jsoup.parse(detailHtml, base), 425737L, base).episodes).isEmpty()
    }
}
