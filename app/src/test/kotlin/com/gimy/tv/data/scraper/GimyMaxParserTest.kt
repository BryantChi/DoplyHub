package com.gimy.tv.data.scraper

import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.data.scraper.parser.GimyMaxParser
import com.google.common.truth.Truth.assertThat
import org.jsoup.Jsoup
import org.junit.Test

class GimyMaxParserTest {
    private val baseUrl = "https://gimy01.co"
    private val detailHtml = """
        <html><head><meta property="og:image" content="https://cdn.picsu.pics/c.jpg"></head>
        <body>
          <h1>家業</h1>
          <p>導演： 李四 主演： 楊紫 年代： 2026 類型： 陸劇 狀態： 更新至34集</p>
          <p>這是一段夠長的劇情介紹用來通過五十字長度門檻所以要多打一些字進來填充它的長度喔。</p>
          <div class="sources route-chips">
            <a class="source is-active" data-id="1">高清線路ᴴᴰ</a>
            <a class="source" data-id="3">無盡雲</a>
          </div>
          <div class="block"><div class="block__head">選集播放</div>
            <div id="1"><a href="/eps/452154-1-1.html">第1集</a><a href="/eps/452154-1-2.html">第2集</a></div>
            <div id="3"><a href="/eps/452154-3-1.html">第1集</a></div>
          </div>
        </body></html>
    """.trimIndent()

    @Test fun `parses detail with eps path and source chip names`() {
        val doc = Jsoup.parse(detailHtml, baseUrl)
        val d = GimyMaxParser.parseVodDetail(doc, 452154L, baseUrl)
        assertThat(d.vod.title).isEqualTo("家業")
        assertThat(d.episodes).hasSize(2)
        val hd = d.episodes.first { it.sourceId == 1 }
        assertThat(hd.sourceName).isEqualTo("高清線路")
        assertThat(hd.episodes[0].playUrl).isEqualTo("/eps/452154-1-1.html")
        assertThat(d.episodes.first { it.sourceId == 3 }.sourceName).isEqualTo("無盡雲")
    }

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
