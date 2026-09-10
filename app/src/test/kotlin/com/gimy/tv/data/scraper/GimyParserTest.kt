package com.gimy.tv.data.scraper

import com.gimy.tv.data.scraper.parser.GimyParser
import com.gimy.tv.data.scraper.parser.GimyPaths
import com.gimy.tv.data.scraper.parser.extractPlayerJson
import com.gimy.tv.domain.model.SourceType
import com.google.common.truth.Truth.assertThat
import org.jsoup.Jsoup
import org.junit.Test

/**
 * Fixtures are trimmed from pages actually fetched on 2026-09-10 — gimytv.me/type/2.html,
 * gitube.tv/browse/2.html and their detail/play pages. Both mirrors run the same template,
 * so the only thing separating them is the path tokens; that is what these tests pin down.
 */
class GimyParserTest {

    private val gimytvPaths = GimyPaths(list = "/type", detail = "/vod", episode = "/ep")
    private val gitubePaths = GimyPaths(list = "/browse", detail = "/title", episode = "/watch")

    private val gimytvParser = GimyParser(SourceType.GIMYTV, gimytvPaths)
    private val gitubeParser = GimyParser(SourceType.GIMYMAX, gitubePaths)

    private val gimytvBase = "https://gimytv.me"
    private val gitubeBase = "https://gitube.tv"

    // ── list ──

    private val gimytvListHtml = """
        <div class="grid">
          <article class="card card--c3">
            <a href="/vod/483139.html" class="card__thumb" aria-label="早春晴朗">
              <img src="https://imgs.1777cdn.com/upload/vod/20260826-1/5f65b23.jpg" alt="早春晴朗">
              <span class="card__badge">24集全</span>
            </a>
            <a href="/vod/483139.html" class="card__body"><h3 class="card__title">早春晴朗</h3></a>
          </article>
          <article class="card card--c1">
            <a href="/vod/100.html" class="card__thumb" aria-label="電影X">
              <img src="/upload/vod/b.jpg" alt="電影X">
              <span class="card__badge">HD</span>
            </a>
            <a href="/vod/100.html" class="card__body"><h3 class="card__title">電影X</h3></a>
          </article>
        </div>
    """.trimIndent()

    private val gitubeListHtml = """
        <div class="grid">
          <article class="card card--c3">
            <a href="/title/464387.html" class="card__thumb" aria-label="早春晴朗">
              <img src="https://cdn.picsu.pics/upload/vod/20260826-1/74f31ca.jpg" alt="早春晴朗">
              <span class="card__badge">全24集</span>
            </a>
            <a href="/title/464387.html" class="card__body"><h3 class="card__title">早春晴朗</h3></a>
          </article>
          <article class="card card--c3">
            <a href="/title/464891.html" class="card__thumb" aria-label="晚風棲野">
              <img src="/upload/vod/c.jpg" alt="晚風棲野">
              <span class="card__badge">更新至12集</span>
            </a>
            <a href="/title/464891.html" class="card__body"><h3 class="card__title">晚風棲野</h3></a>
          </article>
        </div>
    """.trimIndent()

    // Asserts the deprecated `status` on purpose: it is the raw card__badge text the
    // parser is responsible for, whereas siteStatus is a downstream interpretation.
    @Suppress("DEPRECATION")
    @Test fun `parses gimytv cards into vods with id title cover status`() {
        val result = gimytvParser.parseVodList(Jsoup.parse(gimytvListHtml, gimytvBase), gimytvBase, page = 1)
        assertThat(result.items).hasSize(2)
        val first = result.items[0]
        assertThat(first.id).isEqualTo(483139L)
        assertThat(first.title).isEqualTo("早春晴朗")
        assertThat(first.coverUrl).isEqualTo("https://imgs.1777cdn.com/upload/vod/20260826-1/5f65b23.jpg")
        assertThat(first.status).isEqualTo("24集全")
        assertThat(first.sourceType).isEqualTo(SourceType.GIMYTV)
    }

    @Suppress("DEPRECATION")
    @Test fun `parses gitube cards through the same parser with different paths`() {
        val result = gitubeParser.parseVodList(Jsoup.parse(gitubeListHtml, gitubeBase), gitubeBase, page = 1)
        assertThat(result.items).hasSize(2)
        val first = result.items[0]
        assertThat(first.id).isEqualTo(464387L)
        assertThat(first.title).isEqualTo("早春晴朗")
        assertThat(first.status).isEqualTo("全24集")
        assertThat(first.sourceType).isEqualTo(SourceType.GIMYMAX)
    }

    @Test fun `resolves root-relative cover against baseUrl`() {
        val result = gitubeParser.parseVodList(Jsoup.parse(gitubeListHtml, gitubeBase), gitubeBase, page = 1)
        assertThat(result.items[1].coverUrl).isEqualTo("https://gitube.tv/upload/vod/c.jpg")
    }

    /**
     * The 2026-09 outage happened because a parser kept matching after the site moved
     * /vod/ to /title/. Path tokens must actually gate the match — a parser configured for
     * one mirror has to reject the other's markup rather than silently half-succeed.
     */
    @Test fun `path tokens gate the match so a mirror's parser rejects the other's urls`() {
        val gitubeReadingGimytv =
            gitubeParser.parseVodList(Jsoup.parse(gimytvListHtml, gimytvBase), gimytvBase, page = 1)
        assertThat(gitubeReadingGimytv.items).isEmpty()

        val gimytvReadingGitube =
            gimytvParser.parseVodList(Jsoup.parse(gitubeListHtml, gitubeBase), gitubeBase, page = 1)
        assertThat(gimytvReadingGitube.items).isEmpty()
    }

    // ── detail ──

    private fun detailHtml(episodePath: String, vodId: Long) = """
        <html><head><meta property="og:image" content="https://cdn.picsu.pics/c.jpg"></head>
        <body>
          <h1>生逢其時</h1>
          <p>導演： 林嬅 主演： 關曉彤,王子奇 年代： 2026 類型： 內地劇 狀態： 更新至12集</p>
          <p>這是一段夠長的劇情介紹文字用來通過長度門檻超過五十個字所以多寫一些內容填充長度。</p>
          <div class="playlist-block">
            <div class="playlist-block__head"><h2 class="playlist-block__title">愛奇異線路 ᴴᴰ</h2></div>
            <div class="playlist-grid">
              <a href="$episodePath/$vodId-4-1.html">第1集</a>
              <a href="$episodePath/$vodId-4-2.html">第2集</a>
            </div>
          </div>
          <div class="playlist-block">
            <div class="playlist-block__head"><h2 class="playlist-block__title">優酷線路 ᴴᴰ</h2></div>
            <div class="playlist-grid"><a href="$episodePath/$vodId-8-1.html">第1集</a></div>
          </div>
        </body></html>
    """.trimIndent()

    @Test fun `parses gimytv detail line groups with ep token`() {
        val doc = Jsoup.parse(detailHtml("/ep", 443968L), gimytvBase)
        val detail = gimytvParser.parseVodDetail(doc, vodId = 443968L, gimytvBase)
        assertThat(detail.vod.title).isEqualTo("生逢其時")
        assertThat(detail.vod.coverUrl).isEqualTo("https://cdn.picsu.pics/c.jpg")
        assertThat(detail.episodes).hasSize(2)
        val hd = detail.episodes.first { it.sourceName == "愛奇異線路" }
        assertThat(hd.sourceId).isEqualTo(4)
        assertThat(hd.episodes).hasSize(2)
        assertThat(hd.episodes[0].playUrl).isEqualTo("/ep/443968-4-1.html")
        assertThat(detail.episodes.first { it.sourceName == "優酷線路" }.sourceId).isEqualTo(8)
    }

    @Test fun `parses gitube detail line groups with watch token`() {
        val doc = Jsoup.parse(detailHtml("/watch", 465222L), gitubeBase)
        val detail = gitubeParser.parseVodDetail(doc, vodId = 465222L, gitubeBase)
        assertThat(detail.vod.sourceType).isEqualTo(SourceType.GIMYMAX)
        assertThat(detail.episodes).hasSize(2)
        val hd = detail.episodes.first { it.sourceName == "愛奇異線路" }
        assertThat(hd.sourceId).isEqualTo(4)
        assertThat(hd.episodes[0].playUrl).isEqualTo("/watch/465222-4-1.html")
    }

    @Test fun `detail episode token gates the match too`() {
        val doc = Jsoup.parse(detailHtml("/watch", 465222L), gitubeBase)
        val detail = gimytvParser.parseVodDetail(doc, vodId = 465222L, gitubeBase)
        assertThat(detail.episodes).isEmpty()
    }

    // ── player page ──

    /**
     * gitube.tv emits the real blob as `var player_aaaa={...}` and *also* an alias line
     * `player_data=player_aaaa;` further down. Locating by "player_data=" alone lands on the
     * alias, and the next `{` is a chunk of unrelated JS ~20KB later — which is exactly how
     * playback broke. Anchoring on `=` immediately followed by `{` skips the alias.
     */
    @Test fun `extracts player json past the alias assignment`() {
        val html = """
            <script>var player_aaaa={"flag":"play","encrypt":0,"url":"https://x.m3u8","vod_data":{"vod_name":"生逢其時"}}</script>
            <script>player_data=player_aaaa;</script>
            <script>(function(){try{if(!window.matchMedia)return;var s=document;}catch(e){}})()</script>
        """.trimIndent()
        val json = extractPlayerJson(html)
        assertThat(json).startsWith("""{"flag":"play"""")
        assertThat(json).endsWith("}")
        assertThat(json).contains(""""vod_name":"生逢其時"""")
    }

    @Test fun `extracts player json from plain player_data pages`() {
        val html = """<script>var player_data={"flag":"play","encrypt":0,"url":"https://y.m3u8"}</script>"""
        assertThat(extractPlayerJson(html)).isEqualTo("""{"flag":"play","encrypt":0,"url":"https://y.m3u8"}""")
    }

    @Test fun `player json keeps nested braces intact`() {
        val html = """<script>var player_aaaa={"a":{"b":{"c":1}},"d":2}</script>"""
        assertThat(extractPlayerJson(html)).isEqualTo("""{"a":{"b":{"c":1}},"d":2}""")
    }

    @Test fun `player extraction fails loudly when no blob present`() {
        val e = runCatching { extractPlayerJson("<html><body>no player here</body></html>") }.exceptionOrNull()
        assertThat(e).isInstanceOf(ScraperException::class.java)
    }
}
