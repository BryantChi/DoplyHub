package com.gimy.tv.data.scraper

import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.data.scraper.parser.EynyTvParser
import com.google.common.truth.Truth.assertThat
import org.jsoup.Jsoup
import org.junit.Test

class EynyTvParserTest {
    private val baseUrl = "https://eynytv.com"
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
