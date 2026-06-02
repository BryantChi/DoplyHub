package com.gimy.tv.data.scraper

import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.data.scraper.parser.EynyTvParser
import com.google.common.truth.Truth.assertThat
import org.jsoup.Jsoup
import org.junit.Test

class EynyTvParserTest {
    private val baseUrl = "https://eynytv.com"
    private val detailHtml = """
        <html><head><meta property="og:image" content="https://img.avdb.me/c.jpg"></head>
        <body>
          <h1>奪回</h1>
          <p>導演： 王五 主演： 演員A 年代： 2025 類型： 美劇 狀態： 全10集</p>
          <p>這是一段夠長的劇情用以通過長度門檻所以我必須再多打一些中文字湊到超過五十個字元喔好。</p>
          <div class="module-tab module-player-tab">
            <div class="module-tab-item"><span>卧龍雲</span></div>
            <div class="module-tab-item"><span>索尼雲</span></div>
          </div>
          <div class="module-list module-player-list">
            <a href="/vodplay/304462-1-1.html">第1集</a><a href="/vodplay/304462-1-2.html">第2集</a>
          </div>
          <div class="module-list module-player-list">
            <a href="/vodplay/304462-2-1.html">第1集</a>
          </div>
        </body></html>
    """.trimIndent()

    private val listHtml = """
        <div class="module-list">
          <div class="module-item">
            <div class="module-item-pic">
              <a href="/voddetail/304462.html" title="奪回"><i class="icon-play"></i></a>
              <img class="lazy" data-src="https://img.avdb.me/chinaq/a.jpg" src="/loading.png" alt="奪回">
            </div>
            <div class="module-item-caption"><span>2025</span><span class="video-class">劇情</span></div>
          </div>
        </div>
    """.trimIndent()

    @Test fun `parses module-tab lines with vodplay episodes`() {
        val doc = Jsoup.parse(detailHtml, baseUrl)
        val d = EynyTvParser.parseVodDetail(doc, 304462L, baseUrl)
        assertThat(d.episodes).hasSize(2)
        val wolong = d.episodes.first { it.sourceName == "卧龍雲" }
        assertThat(wolong.episodes).hasSize(2)
        assertThat(wolong.episodes[0].playUrl).isEqualTo("/vodplay/304462-1-1.html")
        assertThat(d.episodes.first { it.sourceName == "索尼雲" }.sourceId).isEqualTo(2)
    }

    @Test fun `parses module-item cards with data-src cover`() {
        val doc = Jsoup.parse(listHtml, baseUrl)
        val r = EynyTvParser.parseVodList(doc, baseUrl, 1)
        assertThat(r.items).hasSize(1)
        val v = r.items[0]
        assertThat(v.id).isEqualTo(304462L)
        assertThat(v.title).isEqualTo("奪回")
        assertThat(v.coverUrl).isEqualTo("https://img.avdb.me/chinaq/a.jpg")
        assertThat(v.sourceType).isEqualTo(SourceType.EYNY_TV)
    }
}
