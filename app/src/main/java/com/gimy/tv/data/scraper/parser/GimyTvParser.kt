package com.gimy.tv.data.scraper.parser

import com.gimy.tv.domain.model.Episode
import com.gimy.tv.domain.model.EpisodeGroup
import com.gimy.tv.domain.model.PaginatedResult
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.domain.model.VodDetail
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

    private val stabilityOrder = listOf("順暢", "無盡", "極速", "高清", "騰訊", "藍光", "4K", "優質", "非凡")
    private val epRegex = Regex("/ep/\\d+-(\\d+)-(\\d+)\\.html")

    fun parseVodDetail(doc: Document, vodId: Long, baseUrl: String): VodDetail {
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.let { resolveUrl(it, baseUrl) } ?: ""
        val body = doc.body().text()
        val director = extractMeta(body, "導演")
        val actors = extractMeta(body, "主演").split(Regex("[,，/、]")).map { it.trim() }.filter { it.isNotBlank() }
        val year = (extractMeta(body, "年代") + extractMeta(body, "年份")).filter { it.isDigit() }.take(4).toIntOrNull() ?: 0
        val category = extractMeta(body, "類型").ifBlank { extractMeta(body, "分類") }
        val status = extractMeta(body, "狀態")
        val synopsis = doc.select("p").firstOrNull { it.text().length > 50 && !it.text().contains("導演") }?.text()?.trim() ?: ""

        val groups = mutableListOf<EpisodeGroup>()
        for (block in doc.select("div.playlist-block")) {
            val name = block.selectFirst(".playlist-block__title")?.text()?.trim()
                ?.replace(Regex("\\s*ᴴᴰ\\s*"), "")?.trim() ?: continue
            val eps = mutableListOf<Episode>()
            var sId = 0
            for (link in block.select("a[href~=/ep/]")) {
                val m = epRegex.find(link.attr("href")) ?: continue
                sId = m.groupValues[1].toIntOrNull() ?: continue
                val n = m.groupValues[2].toIntOrNull() ?: continue
                eps.add(Episode(n, link.text().trim(), link.attr("href")))
            }
            if (eps.isNotEmpty() && name.isNotBlank())
                groups.add(EpisodeGroup(name, sId, eps.sortedBy { it.number }))
        }
        val sorted = groups.sortedWith(compareByDescending { g ->
            val i = stabilityOrder.indexOfFirst { g.sourceName.contains(it) }; if (i >= 0) stabilityOrder.size - i else -1
        })
        @Suppress("DEPRECATION")
        return VodDetail(
            Vod(vodId, SourceType.GIMYTV, title, cover, category, year, status,
                siteStatus = parseEpisodeStatus(status)),
            director, actors, synopsis, sorted)
    }

    private fun extractMeta(text: String, key: String): String =
        Regex("$key[：:]\\s*(.+?)(?=\\s+\\S+[：:]|$)").find(text)?.groupValues?.get(1)?.trim() ?: ""
}
