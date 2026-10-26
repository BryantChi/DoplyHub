package com.gimy.tv.data.scraper

import com.google.common.truth.Truth.assertThat
import org.jsoup.Jsoup
import org.junit.Test
import java.time.LocalDate

/**
 * XNXX 2026-10 的網址規則（2026-10-26 實測）：
 * - /best/this_week、this_month、today 全部 301 到同一個月榜 /best/{上個月}，/best 本身也是；
 *   但 /best/N 會被轉回第一頁，所以排行要直接組出月份才翻得了頁。
 * - /tags/{x} 301 到 /search/{x}，/tags/{x}/N 則轉回第一頁。
 * - 頁碼從 0 起算：/search/asian 是第一頁、/search/asian/1 是第二頁。
 * App 的 page 從 1 起算，所以第 N 頁要接 /{N-1}；把 N 直接接上去會跳過一頁。
 */
class XnxxListPathTest {

    private val oct26 = LocalDate.of(2026, 10, 26)

    @Test fun `search path has no page suffix on page 1`() {
        assertThat(xnxxListPath("search/asian", page = 1, today = oct26)).isEqualTo("/search/asian")
    }

    @Test fun `page 2 maps to the zero-based suffix 1`() {
        assertThat(xnxxListPath("search/asian", page = 2, today = oct26)).isEqualTo("/search/asian/1")
    }

    @Test fun `best resolves to last month so it can paginate`() {
        assertThat(xnxxListPath("best", page = 1, today = oct26)).isEqualTo("/best/2026-09")
        assertThat(xnxxListPath("best", page = 3, today = oct26)).isEqualTo("/best/2026-09/2")
    }

    @Test fun `best in january rolls back to december of the previous year`() {
        assertThat(xnxxListPath("best", page = 1, today = LocalDate.of(2027, 1, 5))).isEqualTo("/best/2026-12")
    }

    @Test fun `an empty path falls back to the monthly best`() {
        assertThat(xnxxListPath("", page = 1, today = oct26)).isEqualTo("/best/2026-09")
    }

    /** 分頁連結的尾碼是 0 起算的頁碼；最後一個是 /15 代表 App 的第 16 頁。 */
    @Test fun `max page converts the zero-based tail to a one-based page`() {
        val doc = Jsoup.parse(
            """<div class="pagination "><ul><li><a class="active" href="">1</a></li><li><a href="/search/asian/1">2</a></li><li><a href="/search/asian/15">16</a></li><li><a class="no-page next" href="/search/asian/1">Next</a></li></ul></div>"""
        )
        assertThat(xnxxMaxPage(doc)).isEqualTo(16)
    }

    @Test fun `no pagination block yields null`() {
        assertThat(xnxxMaxPage(Jsoup.parse("<div>no pages</div>"))).isNull()
    }
}
