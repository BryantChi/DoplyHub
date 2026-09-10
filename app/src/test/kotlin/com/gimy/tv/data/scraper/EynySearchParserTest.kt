package com.gimy.tv.data.scraper

import com.gimy.tv.data.scraper.parser.EynyTvParser
import com.gimy.tv.domain.model.SourceType
import com.google.common.truth.Truth.assertThat
import org.jsoup.Jsoup
import org.junit.Test

/**
 * eynytv.com 的搜尋頁與列表頁是**不同模板**。
 *
 * 列表頁的卡片是 `.module-item`，搜尋頁卻是 `.module-search-item`——實測（2026-09-11）
 * 搜尋頁上 `.module-item` 的數量是 0。原本 search() 直接沿用 parseVodList，於是 Eyny
 * 每一次搜尋都回 0 筆，在聚合結果裡靜默缺席，看起來就像「這個站沒有這部片」。
 *
 * 下面的 HTML 取自實際的 `/vodsearch/-------------.html?wd=恐怖` 回應。
 */
class EynySearchParserTest {
    private val baseUrl = "https://eynytv.com"

    private val searchHtml = """
        <html><body>
        <div class="module-search-item">
          <div class="video-cover"><div class="module-item-cover"><div class="module-item-pic">
            <a href="/voddetail/10748.html" title="恐怖分子的孩子"><i class="icon-play"></i></a>
            <img class="lazy lazyload"
                 data-src="https://img.avdb.me/eyny/upload/vod/20210518-31/ad12da2a.jpg"
                 src="/template/dianyingim/img/loading.png" alt="恐怖分子的孩子">
          </div></div></div>
          <div class="video-info"><div class="video-info-header">
            <a class="video-serial" href="/voddetail/10748.html" title="恐怖分子的孩子">HD中字</a>
            <h3><a href="/voddetail/10748.html" title="恐怖分子的孩子">恐怖分子的孩子</a></h3>
            <div class="video-info-aux">
              <a href="javascript:;" title="恐怖片" class="tag-link"><span class="video-tag-icon">恐怖片</span></a>
              <div class="tag-link"><a href="/vodsearch/-------------2017.html">2017</a></div>
            </div>
          </div></div>
        </div>
        <div class="module-search-item">
          <div class="video-cover"><div class="module-item-cover"><div class="module-item-pic">
            <a href="/voddetail/20999.html" title="恐怖遊輪"><i class="icon-play"></i></a>
            <img class="lazy lazyload"
                 data-src="https://img.avdb.me/eyny/upload/vod/b.jpg"
                 src="/template/dianyingim/img/loading.png" alt="恐怖遊輪">
          </div></div></div>
          <div class="video-info"><div class="video-info-header">
            <a class="video-serial" href="/voddetail/20999.html" title="恐怖遊輪">正片</a>
            <h3><a href="/voddetail/20999.html" title="恐怖遊輪">恐怖遊輪</a></h3>
          </div></div>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `解析搜尋頁的 module-search-item 卡片`() {
        val result = EynyTvParser.parseSearchResults(Jsoup.parse(searchHtml, baseUrl), baseUrl, page = 1)

        assertThat(result.items).hasSize(2)
        assertThat(result.items.map { it.title }).containsExactly("恐怖分子的孩子", "恐怖遊輪").inOrder()
        assertThat(result.items[0].id).isEqualTo(10748L)
        assertThat(result.items[0].sourceType).isEqualTo(SourceType.EYNY_TV)
    }

    @Test
    fun `封面必須取 data-src 而不是佔位圖`() {
        // src 是站方的 lazyload 佔位圖（loading.png）。取錯的話每張卡片都會變成同一張灰圖，
        // 而且因為 HTTP 200 不會有任何錯誤跡象。
        val result = EynyTvParser.parseSearchResults(Jsoup.parse(searchHtml, baseUrl), baseUrl, page = 1)

        assertThat(result.items[0].coverUrl).endsWith("ad12da2a.jpg")
        assertThat(result.items[0].coverUrl).doesNotContain("loading.png")
    }

    @Test
    fun `帶回更新狀態`() {
        val result = EynyTvParser.parseSearchResults(Jsoup.parse(searchHtml, baseUrl), baseUrl, page = 1)

        assertThat(result.items[0].status).isEqualTo("HD中字")
        assertThat(result.items[1].status).isEqualTo("正片")
    }

    @Test
    fun `沒有結果時回空清單`() {
        val empty = """<html><body><div class="module-search-empty">沒有找到</div></body></html>"""

        val result = EynyTvParser.parseSearchResults(Jsoup.parse(empty, baseUrl), baseUrl, page = 1)

        assertThat(result.items).isEmpty()
        assertThat(result.hasMore).isFalse()
    }

    @Test
    fun `列表頁模板進到搜尋解析也不會誤判`() {
        // 防漂移：站方若把搜尋頁換回 module-item，這個測試會提醒兩邊要一起改。
        val listTemplate = """
            <html><body><div class="module-item">
              <a href="/voddetail/1.html" title="列表片"><img data-src="https://x/a.jpg"></a>
            </div></body></html>
        """.trimIndent()

        val result = EynyTvParser.parseSearchResults(Jsoup.parse(listTemplate, baseUrl), baseUrl, page = 1)

        assertThat(result.items).isEmpty()
    }
}
