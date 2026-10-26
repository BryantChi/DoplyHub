package com.gimy.tv.data.scraper

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * MacCms 站台的列表分頁網址不一致。momovod 的靜態路徑 /type/{id}-{page}.html 能翻頁；
 * gimy.tw 2026-10 起 /vodtype/{id}-{page}.html 永遠回第 1 頁的內容（「載入更多」一直重複），
 * 只有 /vodshow/{id}--------{page}---.html 會真的翻頁、且保留分類（2026-10-26 逐一驗過 13 個分類）。
 */
class MacCmsListUrlTest {

    @Test fun `static style uses the bare path on page 1`() {
        val url = macCmsListUrl("https://momovod.app", "/type", typeId = 2, page = 1, MacCmsListStyle.STATIC)
        assertThat(url).isEqualTo("https://momovod.app/type/2.html")
    }

    @Test fun `static style appends the page number beyond page 1`() {
        val url = macCmsListUrl("https://momovod.app", "/type", typeId = 2, page = 3, MacCmsListStyle.STATIC)
        assertThat(url).isEqualTo("https://momovod.app/type/2-3.html")
    }

    /** 第 1 頁也要走 vodshow：/vodtype 第 1 頁是各子分類的總覽，接 vodshow 第 2 頁會漏片又重複。 */
    @Test fun `vodshow style uses vodshow even on page 1`() {
        val url = macCmsListUrl("https://gimy.tw", "/vodtype", typeId = 2, page = 1, MacCmsListStyle.VODSHOW)
        assertThat(url).isEqualTo("https://gimy.tw/vodshow/2--------1---.html")
    }

    @Test fun `vodshow style puts the page in the ninth slot`() {
        val url = macCmsListUrl("https://gimy.tw", "/vodtype", typeId = 15, page = 2, MacCmsListStyle.VODSHOW)
        assertThat(url).isEqualTo("https://gimy.tw/vodshow/15--------2---.html")
    }
}
