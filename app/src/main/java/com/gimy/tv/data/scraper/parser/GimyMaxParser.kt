package com.gimy.tv.data.scraper.parser

import com.gimy.tv.domain.model.Episode
import com.gimy.tv.domain.model.EpisodeGroup
import com.gimy.tv.domain.model.PaginatedResult
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.domain.model.VodDetail
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

    private val stabilityOrder = listOf("順暢", "無盡", "極速", "高清", "騰訊", "藍光", "4K", "優質", "非凡")
    private val epsRegex = Regex("/eps/\\d+-(\\d+)-(\\d+)\\.html")

    fun parseVodDetail(doc: Document, vodId: Long, baseUrl: String): VodDetail {
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { resolveUrl(it, baseUrl) } ?: ""
        val body = doc.body().text()
        val director = extractMeta(body, "導演")
        val actors = extractMeta(body, "主演").split(Regex("[,，/、]")).map { it.trim() }.filter { it.isNotBlank() }
        val year = (extractMeta(body, "年代") + extractMeta(body, "年份")).filter { it.isDigit() }.take(4).toIntOrNull() ?: 0
        val category = extractMeta(body, "類型").ifBlank { extractMeta(body, "分類") }
        val status = extractMeta(body, "狀態")
        val synopsis = doc.select("p").firstOrNull { it.text().length > 50 && !it.text().contains("導演") }?.text()?.trim() ?: ""

        val nameById = mutableMapOf<Int, String>()
        for (chip in doc.select(".sources .source[data-id]")) {
            val sid = chip.attr("data-id").toIntOrNull() ?: continue
            nameById[sid] = chip.text().trim().replace(Regex("\\s*ᴴᴰ\\s*"), "").trim()
        }
        val groups = mutableListOf<EpisodeGroup>()
        for (blk in doc.select("div.block div[id]")) {
            val sid = blk.id().toIntOrNull() ?: continue
            val eps = mutableListOf<Episode>()
            for (link in blk.select("a[href~=/eps/]")) {
                val m = epsRegex.find(link.attr("href")) ?: continue
                val n = m.groupValues[2].toIntOrNull() ?: continue
                eps.add(Episode(n, link.text().trim(), link.attr("href")))
            }
            if (eps.isNotEmpty()) {
                val name = nameById[sid] ?: "線路 $sid"
                groups.add(EpisodeGroup(name, sid, eps.sortedBy { it.number }))
            }
        }
        val sorted = groups.sortedWith(compareByDescending { g ->
            val i = stabilityOrder.indexOfFirst { g.sourceName.contains(it) }; if (i >= 0) stabilityOrder.size - i else -1
        })
        @Suppress("DEPRECATION")
        return VodDetail(
            Vod(vodId, SourceType.GIMYMAX, title, cover, category, year, status,
                siteStatus = parseEpisodeStatus(status)),
            director, actors, synopsis, sorted)
    }

    private fun extractMeta(text: String, key: String): String =
        Regex("$key[：:]\\s*(.+?)(?=\\s+\\S+[：:]|$)").find(text)?.groupValues?.get(1)?.trim() ?: ""

    internal fun resolveUrl(url: String, baseUrl: String): String = when {
        url.isBlank() -> ""
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$baseUrl$url"
        else -> url
    }
}
