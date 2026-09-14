package com.gimy.tv.data.scraper

import com.gimy.tv.data.scraper.parser.attrText
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.data.scraper.parser.GimyPaths
import com.gimy.tv.data.scraper.parser.GimyParser
import com.google.common.truth.Truth.assertThat
import org.jsoup.Jsoup
import org.junit.Test

/**
 * 卡片標題的 HTML entity 解碼。
 *
 * 實機在「電視劇」分類第一格看到片名顯示成「S&amp;X」。根因不是 Jsoup 沒解碼——
 * 它的 attr() 本來就會解一次——而是**站台把片名編碼了兩次**：
 * 原始 HTML 是 `aria-label="S&amp;amp;X"`（2026-09-15 curl 實測 gimytv.me）。
 *
 * 所以這裡測的一律是雙重編碼。單重編碼 Jsoup 自己就處理掉了，拿它當測資
 * 會得到一個永遠綠的假測試——第一版就是這樣寫的，突變測試才抓出來。
 */
class AttrTextTest {

    private fun el(html: String) = Jsoup.parse(html).selectFirst("a")!!

    @Test
    fun `屬性裡的 entity 要解碼`() {
        assertThat(el("""<a title="S&amp;amp;X">x</a>""").attrText("title")).isEqualTo("S&X")
        assertThat(el("""<a aria-label="R&amp;amp;B 之夜">x</a>""").attrText("aria-label")).isEqualTo("R&B 之夜")
    }

    @Test
    fun `引號與單引號也要解`() {
        assertThat(el("""<a title="他說&amp;quot;好&amp;quot;">x</a>""").attrText("title")).isEqualTo("他說\"好\"")
        assertThat(el("""<a title="Don&amp;#39;t Look Up">x</a>""").attrText("title")).isEqualTo("Don't Look Up")
    }

    @Test
    fun `沒有 entity 的原樣回傳並去掉頭尾空白`() {
        assertThat(el("""<a title="  斗羅大陸  ">x</a>""").attrText("title")).isEqualTo("斗羅大陸")
    }

    @Test
    fun `屬性不存在時回空字串`() {
        assertThat(el("""<a>x</a>""").attrText("title")).isEmpty()
    }
}

/**
 * 整條解析路徑的迴歸測試——確認各站的卡片標題真的走了 [attrText]。
 *
 * 只測 [attrText] 本身不夠：呼叫端只要有一處漏改，畫面上照樣會出現「S&amp;X」。
 * 這裡餵的是實機在電視劇分類真的遇到的那個形狀（aria-label 帶 &amp;）。
 */
class ParserEntityDecodingTest {

    private val parser = GimyParser(
        SourceType.GIMYTV,
        GimyPaths(list = "/type", detail = "/vod", episode = "/ep"),
    )
    private val base = "https://gimytv.me"

    @Test
    fun `列表卡片的 aria-label 標題解碼 entity`() {
        val html = """
            <div class="grid">
              <article class="card card--c3">
                <a href="/vod/1.html" class="card__thumb" aria-label="S&amp;amp;X">
                  <img src="/a.jpg">
                  <span class="card__badge">12集</span>
                </a>
              </article>
            </div>
        """.trimIndent()
        val result = parser.parseVodList(Jsoup.parse(html, base), base, page = 1)
        assertThat(result.items.map { it.title }).containsExactly("S&X")
    }

    @Test
    fun `標題退回 img alt 時也要解碼`() {
        val html = """
            <div class="grid">
              <article class="card card--c3">
                <a href="/vod/2.html" class="card__thumb">
                  <img src="/b.jpg" alt="R&amp;amp;B 之夜">
                </a>
              </article>
            </div>
        """.trimIndent()
        val result = parser.parseVodList(Jsoup.parse(html, base), base, page = 1)
        assertThat(result.items.map { it.title }).containsExactly("R&B 之夜")
    }

    @Test
    fun `搜尋結果的標題同樣要解碼`() {
        val html = """
            <div class="search-list">
              <div class="search-item">
                <a href="/vod/3.html" class="search-item__thumb" aria-label="Tom &amp;amp; Jerry">
                  <img src="/c.jpg">
                </a>
              </div>
            </div>
        """.trimIndent()
        val result = parser.parseSearchResults(Jsoup.parse(html, base), base, page = 1)
        assertThat(result.items.map { it.title }).containsExactly("Tom & Jerry")
    }
}
