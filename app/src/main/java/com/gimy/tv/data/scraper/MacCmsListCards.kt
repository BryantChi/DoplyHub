package com.gimy.tv.data.scraper

import com.gimy.tv.data.scraper.parser.attrText
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.domain.util.parseEpisodeStatus
import org.jsoup.nodes.Element

/**
 * 把 MacCms 列表／搜尋頁 [scope] 內的卡片解析成 Vod。
 *
 * 從 MacCmsListBasedSource.parseVodList 抽出來，是為了能在 JVM 上直接拿線上 HTML 片段測試——
 * 這幾個站改版時都是卡片版型先變，而原本的寫法要建出整個 source 才測得到。
 */
internal fun parseMacCmsCards(
    scope: Element,
    detailUrlPath: String,
    sourceType: SourceType,
    resolveUrl: (String) -> String,
): List<Vod> {
    val items = mutableListOf<Vod>()
    val detailHrefRegex = Regex("$detailUrlPath/(\\d+)\\.html")
    // 不能再要求 [data-original]：2026-10 起 gimy.tw 列表卡片拿掉了它（封面改放內層 img），
    // 帶著這個條件整頁會是 0 筆。真正擋掉非卡片連結的是下面的 detailHrefRegex 與片名檢查。
    for (card in scope.select("a.myui-vodlist__thumb, a[class*=video-pic]")) {
        val href = card.attr("href")
        val id = detailHrefRegex.find(href)?.groupValues?.get(1)?.toLongOrNull() ?: continue
        val title = card.attrText("title").ifBlank {
            card.parent()?.selectFirst(".myui-vodlist__detail h4 a")?.text()?.trim().orEmpty()
        }
        if (title.isBlank()) continue
        val cover = resolveUrl(macCmsCardCover(card))
        val badge = card.selectFirst("span.pic-text, span.note")?.text()?.trim() ?: ""
        // gimy.tw 搜尋頁的角標放的是片名，集數在同一列的「狀態：」欄；找不到就留空，
        // 不能把片名當狀態顯示。
        val status = if (badge != title) badge else searchRowStatus(card)
        items.add(Vod(id, sourceType, title, cover, "", 0, status,
            siteStatus = parseEpisodeStatus(status)))
    }
    return items
}

/** gimy.tw 搜尋結果列裡 `<li><span>狀態：</span>第4集</li>` 的值。 */
private fun searchRowStatus(card: Element): String =
    card.parents().firstOrNull { it.hasClass("details-info-min") }
        ?.select("li")?.firstOrNull { li -> li.selectFirst("span")?.text()?.startsWith("狀態") == true }
        ?.ownText()?.trim()
        .orEmpty()
