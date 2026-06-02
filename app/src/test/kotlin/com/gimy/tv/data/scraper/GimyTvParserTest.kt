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
}
