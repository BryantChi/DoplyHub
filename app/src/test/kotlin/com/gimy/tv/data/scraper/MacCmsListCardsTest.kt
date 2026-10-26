package com.gimy.tv.data.scraper

import com.gimy.tv.domain.model.SourceType
import com.google.common.truth.Truth.assertThat
import org.jsoup.Jsoup
import org.junit.Test

/**
 * MacCms 列表／搜尋卡片的解析。fixture 取自 2026-10-26 的線上頁面（只留解析用得到的部分）。
 *
 * 斷言刻意用已棄用的 `status`：它是卡片上的原始文字，正是這層解析要負責的東西；
 * siteStatus 是下游的再詮釋。
 */
@Suppress("DEPRECATION")
class MacCmsListCardsTest {

    private val resolve: (String) -> String = { if (it.startsWith("/")) "https://gimy.tw$it" else it }

    /**
     * gimy.tw 列表卡片拿掉了 data-original，封面改放內層 img。舊選擇器要求 data-original，
     * 結果整頁 0 筆、探測判成失效、首頁 Gimy 每一列都是空的。
     */
    @Test fun `gimy tw list cards without data-original are still parsed`() {
        val html = """
            <a class="video-pic site-cover" href="/voddetail/340457.html" title="警察世家：波士頓第二季"><img class="site-cover-img" src="https://img.azgo.me/chinaq/upload/vod/3c45.jpg" alt="警察世家：波士頓第二季"> <span class="player"></span> <span class="note text-bg-r">第3集</span> </a>
            <a class="video-pic site-cover" href="/voddetail/202051.html" title="鋼鐵森林"><img class="site-cover-img" src="https://img.azgo.me/chinaq/upload/vod/a1b2.jpg"> <span class="note text-bg-r">全13集</span> </a>
        """.trimIndent()
        val items = parseMacCmsCards(Jsoup.parse(html), "/voddetail", SourceType.GIMY_TW, resolve)

        assertThat(items.map { it.id }).containsExactly(340457L, 202051L).inOrder()
        val first = items[0]
        assertThat(first.title).isEqualTo("警察世家：波士頓第二季")
        assertThat(first.coverUrl).isEqualTo("https://img.azgo.me/chinaq/upload/vod/3c45.jpg")
        assertThat(first.status).isEqualTo("第3集")
    }

    /** momovod 的卡片選擇器一直命中，但封面同樣搬到內層 img，之前全部是空白圖。 */
    @Test fun `momovod list cards pick the inner img cover`() {
        val html = """
            <a class="myui-vodlist__thumb site-cover" href="/vod/529906.html" title="富閨可期"><img class="site-cover-img" src="https://img.azgo.me/chinaq/upload/vod/8c61.jpg" alt="富閨可期"><span class="pic-tag pic-tag-top">0.0分</span><span class="pic-text text-center">更新至19集</span></a>
        """.trimIndent()
        val items = parseMacCmsCards(Jsoup.parse(html), "/vod", SourceType.MOMOVOD, resolve)

        assertThat(items).hasSize(1)
        assertThat(items[0].coverUrl).isEqualTo("https://img.azgo.me/chinaq/upload/vod/8c61.jpg")
        assertThat(items[0].status).isEqualTo("更新至19集")
    }

    /**
     * gimy.tw 搜尋頁的卡片角標放的是片名，不是集數；集數在同一列右側的「狀態：」欄。
     * 直接拿角標會讓狀態欄顯示片名。
     */
    @Test fun `gimy tw search cards take status from the row not the title badge`() {
        val html = """
            <div class="details-info-min col-md-12 clearfix news-box-txt p-0">
              <div class="col-md-3 news-box-txt-l clearfix"><a class="video-pic loading" href="/voddetail/322735.html" title="黑暗召喚師在地下戀愛中" data-original="https://img.azgo.me/chinaq/upload/vod/542f.jpg"><span class="note text-bg-c">黑暗召喚師在地下戀愛中</span></a></div>
              <div class="col-md-9 clearfix pb-0"><div class="details-info p-0"><ul class="info clearfix">
                <li class="col-md-6"><a href="/voddetail/322735.html" title="黑暗召喚師在地下戀愛中">黑暗召喚師在地下戀愛中</a><span class="hidden-sm hidden-md hidden-lg">第4集</span></li>
                <li class="col-md-12 hidden-xs"><span>狀態：</span>第4集</li>
                <li class="col-md-12 text"><span class="hidden-xs">類型：</span><a href="/vodtype/30.html">日韓動漫</a></li>
              </ul></div></div>
            </div>
        """.trimIndent()
        val items = parseMacCmsCards(Jsoup.parse(html), "/voddetail", SourceType.GIMY_TW, resolve)

        assertThat(items).hasSize(1)
        assertThat(items[0].coverUrl).isEqualTo("https://img.azgo.me/chinaq/upload/vod/542f.jpg")
        assertThat(items[0].status).isEqualTo("第4集")
    }

    /** 角標等於片名、又找不到「狀態：」欄時，寧可留空，也不要把片名當狀態。 */
    @Test fun `a title-only badge with no status row yields empty status`() {
        val html = """<a class="video-pic" href="/voddetail/7.html" title="某片" data-original="https://x/7.jpg"><span class="note">某片</span></a>"""
        val items = parseMacCmsCards(Jsoup.parse(html), "/voddetail", SourceType.GIMY_TW, resolve)

        assertThat(items[0].status).isEmpty()
    }
}
