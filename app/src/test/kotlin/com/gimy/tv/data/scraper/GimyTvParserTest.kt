package com.gimy.tv.data.scraper

import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.data.scraper.parser.GimyTvParser
import com.google.common.truth.Truth.assertThat
import org.jsoup.Jsoup
import org.junit.Test

class GimyTvParserTest {
    private val baseUrl = "https://gimyplus.com"

    private val listHtml = """
        <div class="grid">
          <article>
            <a href="/vod/443968.html" class="card__thumb" aria-label="雨霖鈴">
              <img src="https://imgs.1777cdn.com/upload/vod/a.jpg" alt="雨霖鈴">
              <span class="card__badge">更新至33集</span>
            </a>
            <a href="/vod/443968.html" class="card__body">
              <h3 class="card__title">雨霖鈴</h3>
            </a>
          </article>
          <article>
            <a href="/vod/100.html" class="card__thumb" aria-label="電影X">
              <img src="/upload/vod/b.jpg" alt="電影X">
              <span class="card__badge">HD</span>
            </a>
            <a href="/vod/100.html" class="card__body"><h3 class="card__title">電影X</h3></a>
          </article>
        </div>
    """.trimIndent()

    @Test fun `parses cards into vods with id title cover status`() {
        val doc = Jsoup.parse(listHtml, baseUrl)
        val result = GimyTvParser.parseVodList(doc, baseUrl, page = 1)
        assertThat(result.items).hasSize(2)
        val first = result.items[0]
        assertThat(first.id).isEqualTo(443968L)
        assertThat(first.title).isEqualTo("雨霖鈴")
        assertThat(first.coverUrl).isEqualTo("https://imgs.1777cdn.com/upload/vod/a.jpg")
        assertThat(first.status).isEqualTo("更新至33集")
        assertThat(first.sourceType).isEqualTo(SourceType.GIMYTV)
    }

    @Test fun `resolves root-relative cover against baseUrl`() {
        val doc = Jsoup.parse(listHtml, baseUrl)
        val result = GimyTvParser.parseVodList(doc, baseUrl, page = 1)
        assertThat(result.items[1].coverUrl).isEqualTo("https://gimyplus.com/upload/vod/b.jpg")
    }

    private val detailHtml = """
        <html><head><meta property="og:image" content="https://imgs.1777cdn.com/c.jpg"></head>
        <body>
          <h1>雨霖鈴</h1>
          <p>導演： 張三 主演： 楊洋,章若楠 年代： 2026 類型： 陸劇 狀態： 更新至33集</p>
          <p>這是一段夠長的劇情介紹文字用來通過長度門檻超過五十個字所以多寫一些內容填充長度。</p>
          <div class="playlist-block">
            <div class="playlist-block__head"><span class="playlist-block__title">高清線路 ᴴᴰ</span></div>
            <div class="playlist-grid">
              <a href="/ep/443968-1-1.html">第1集</a>
              <a href="/ep/443968-1-2.html">第2集</a>
            </div>
          </div>
          <div class="playlist-block">
            <div class="playlist-block__head"><span class="playlist-block__title">優酷線路 ᴴᴰ</span></div>
            <div class="playlist-grid"><a href="/ep/443968-8-1.html">第1集</a></div>
          </div>
        </body></html>
    """.trimIndent()

    @Test fun `parses detail title cover and line groups`() {
        val doc = Jsoup.parse(detailHtml, baseUrl)
        val detail = GimyTvParser.parseVodDetail(doc, vodId = 443968L, baseUrl)
        assertThat(detail.vod.title).isEqualTo("雨霖鈴")
        assertThat(detail.vod.coverUrl).isEqualTo("https://imgs.1777cdn.com/c.jpg")
        assertThat(detail.episodes).hasSize(2)
        val hd = detail.episodes.first { it.sourceName == "高清線路" }
        assertThat(hd.sourceId).isEqualTo(1)
        assertThat(hd.episodes).hasSize(2)
        assertThat(hd.episodes[0].playUrl).isEqualTo("/ep/443968-1-1.html")
        assertThat(detail.episodes.first { it.sourceName == "優酷線路" }.sourceId).isEqualTo(8)
    }
}
