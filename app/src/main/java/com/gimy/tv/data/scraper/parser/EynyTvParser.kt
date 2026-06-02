package com.gimy.tv.data.scraper.parser

import com.gimy.tv.domain.model.PaginatedResult
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.domain.util.parseEpisodeStatus
import org.jsoup.nodes.Document

object EynyTvParser {
    private val vodIdRegex = Regex("/voddetail/(\\d+)\\.html")

    fun parseVodList(doc: Document, baseUrl: String, page: Int): PaginatedResult<Vod> {
        doc.select("#stickyside, .myui-side, aside").remove()
        val items = mutableListOf<Vod>()
        for (item in doc.select(".module-item")) {
            val link = item.selectFirst("a[href*=/voddetail/]") ?: continue
            val id = vodIdRegex.find(link.attr("href"))?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val title = link.attr("title").trim().ifBlank {
                item.selectFirst("img")?.attr("alt")?.trim().orEmpty()
            }
            if (title.isBlank()) continue
            val img = item.selectFirst("img")
            val cover = resolveUrl(
                img?.attr("data-src").orEmpty().ifBlank { img?.attr("data-original").orEmpty() }, baseUrl)
            val status = item.selectFirst(".module-item-note, .pic-text, span.note")?.text()?.trim() ?: ""
            @Suppress("DEPRECATION")
            items.add(Vod(id, SourceType.EYNY_TV, title, cover, "", 0, status,
                siteStatus = parseEpisodeStatus(status)))
        }
        val unique = items.distinctBy { it.id }
        val hasNext = doc.select("a:contains(下一頁), a:contains(下一页), a.next").isNotEmpty()
        val totalPages = Regex("(\\d+)/(\\d+)").find(doc.select(".myui-page, .page, .pagination").text())
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
