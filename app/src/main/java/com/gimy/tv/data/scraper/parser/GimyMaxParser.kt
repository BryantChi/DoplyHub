package com.gimy.tv.data.scraper.parser

import com.gimy.tv.domain.model.PaginatedResult
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.domain.util.parseEpisodeStatus
import org.jsoup.nodes.Document

/** Pure parser for gimy01.co (GIMYMAX) new poster-card template. No network. */
object GimyMaxParser {
    private val vodIdRegex = Regex("/vod/(\\d+)\\.html")

    fun parseVodList(doc: Document, baseUrl: String, page: Int): PaginatedResult<Vod> {
        doc.select("#stickyside, .rankings").remove()
        val items = mutableListOf<Vod>()
        for (card in doc.select("a.poster[href*=/vod/]")) {
            val id = vodIdRegex.find(card.attr("href"))?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val title = card.selectFirst("h3.poster__title")?.text()?.trim()
                ?.ifBlank { card.selectFirst("img")?.attr("alt")?.trim() } ?: ""
            if (title.isBlank()) continue
            val cover = resolveUrl(card.selectFirst(".poster__thumb img")?.attr("src").orEmpty(), baseUrl)
            val status = card.selectFirst("span.poster__status")?.text()?.trim() ?: ""
            @Suppress("DEPRECATION")
            items.add(Vod(id, SourceType.GIMYMAX, title, cover, "", 0, status,
                siteStatus = parseEpisodeStatus(status)))
        }
        val unique = items.distinctBy { it.id }
        val hasNext = doc.select("a:contains(下一頁), a.next, a[title=下一頁]").isNotEmpty()
        val totalPages = Regex("(\\d+)/(\\d+)").find(doc.select(".page, .pagination").text())
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
