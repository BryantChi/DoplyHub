package com.gimy.tv.data.scraper

import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.data.scraper.parser.GimyMaxParser
import com.google.common.truth.Truth.assertThat
import org.jsoup.Jsoup
import org.junit.Test

class GimyMaxParserTest {
    private val baseUrl = "https://gimy01.co"
    private val listHtml = """
        <div class="grid">
          <a class="poster" href="/vod/452154.html">
            <span class="poster__thumb">
              <img src="https://cdn.picsu.pics/upload/vod/a.jpg" alt="家業">
              <span class="poster__status">更新至34集</span>
            </span>
            <h3 class="poster__title">家業</h3>
            <p class="poster__meta">楊紫</p>
          </a>
        </div>
    """.trimIndent()

    @Test fun `parses poster cards`() {
        val doc = Jsoup.parse(listHtml, baseUrl)
        val r = GimyMaxParser.parseVodList(doc, baseUrl, 1)
        assertThat(r.items).hasSize(1)
        val v = r.items[0]
        assertThat(v.id).isEqualTo(452154L)
        assertThat(v.title).isEqualTo("家業")
        assertThat(v.coverUrl).isEqualTo("https://cdn.picsu.pics/upload/vod/a.jpg")
        assertThat(v.status).isEqualTo("更新至34集")
        assertThat(v.sourceType).isEqualTo(SourceType.GIMYMAX)
    }
}
