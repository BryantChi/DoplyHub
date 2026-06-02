package com.gimy.tv.data.scraper.parser

import com.gimy.tv.domain.model.PaginatedResult
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.domain.util.parseEpisodeStatus
import org.jsoup.nodes.Document

/** 純解析 gimyplus.com(GIMYTV)新模板,不碰網路。Source 委派之,測試直接呼叫。 */
object GimyTvParser {
    private val vodIdRegex = Regex("/vod/(\\d+)\\.html")

    fun parseVodList(doc: Document, baseUrl: String, page: Int): PaginatedResult<Vod> {
        doc.select("#stickyside, .rankings, .rank-list").remove()
        val items = mutableListOf<Vod>()
        for (card in doc.select("a.card__thumb[href*=/vod/]")) {
            val id = vodIdRegex.find(card.attr("href"))?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val title = card.attr("aria-label").ifBlank {
                card.selectFirst("img")?.attr("alt").orEmpty()
            }.ifBlank {
                card.parent()?.selectFirst("a.card__body h3.card__title")?.text().orEmpty()
            }.trim()
            if (title.isBlank()) continue
            val cover = resolveUrl(card.selectFirst("img")?.attr("src").orEmpty(), baseUrl)
            val status = card.selectFirst("span.card__badge")?.text()?.trim() ?: ""
            @Suppress("DEPRECATION")
            items.add(Vod(id, SourceType.GIMYTV, title, cover, "", 0, status,
                siteStatus = parseEpisodeStatus(status)))
        }
        val unique = items.distinctBy { it.id }
        val hasNext = doc.select("a:contains(下一頁), a[title=下一頁], .chip-nav--next").isNotEmpty()
        val totalPages = Regex("(\\d+)/(\\d+)").find(doc.select(".page, .stui-page, .section__nav").text())
            ?.groupValues?.get(2)?.toIntOrNull() ?: if (hasNext) page + 1 else page
        return PaginatedResult(unique, page, totalPages, hasNext || page < totalPages)
    }

    internal fun resolveUrl(url: String, baseUrl: String): String = when {
        url.isBlank() -> ""
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$baseUrl$url"
        else -> url
    }
}
