package com.gimy.tv.data.scraper.parser

import com.gimy.tv.data.scraper.ScraperException
import com.gimy.tv.domain.model.Episode
import com.gimy.tv.domain.model.EpisodeGroup
import com.gimy.tv.domain.model.PaginatedResult
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.domain.model.VodDetail
import com.gimy.tv.domain.util.parseEpisodeStatus
import org.jsoup.nodes.Document

/**
 * Path tokens that differ between Gimy mirrors running the same HTML template.
 *
 * gimytv.me and gitube.tv serve byte-identical card markup and detail layout; only these
 * segments differ. Keeping them in one object means switching a mirror is a single edit
 * instead of hunting constants across parser and source.
 *
 * [list] is used by the Source to build URLs; the parser only needs [detail] and [episode].
 */
data class GimyPaths(
    val list: String,      // "/type"  | "/browse"
    val detail: String,    // "/vod"   | "/title"
    val episode: String,   // "/ep"    | "/watch"
)

/**
 * Parses the Gimy "card" template shared by gimytv.me (GIMYTV) and gitube.tv (GIMYMAX).
 * Network-free: Sources delegate here, tests call it directly.
 *
 * Path tokens are constructor state rather than constants because a mirror swap changes
 * only those. They also gate matching — a parser built for one mirror yields nothing on
 * the other's markup instead of half-parsing it, which is what made the 2026-09 outage
 * silent (HTTP 200, zero items, no error).
 */
class GimyParser(
    private val sourceType: SourceType,
    private val paths: GimyPaths,
) {
    private val vodIdRegex = Regex("${Regex.escape(paths.detail)}/(\\d+)\\.html")
    private val epRegex = Regex("${Regex.escape(paths.episode)}/\\d+-(\\d+)-(\\d+)\\.html")
    private val cardSelector = "a.card__thumb[href*=${paths.detail}/]"
    private val episodeSelector = "a[href~=${paths.episode}/]"

    fun parseVodList(doc: Document, baseUrl: String, page: Int): PaginatedResult<Vod> {
        doc.select("#stickyside, .rankings, .rank-list").remove()
        val items = mutableListOf<Vod>()
        for (card in doc.select(cardSelector)) {
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
            items.add(Vod(id, sourceType, title, cover, "", 0, status,
                siteStatus = parseEpisodeStatus(status)))
        }
        val unique = items.distinctBy { it.id }
        val hasNext = doc.select("a:contains(下一頁), a[title=下一頁], .chip-nav--next").isNotEmpty()
        val totalPages = Regex("(\\d+)/(\\d+)").find(doc.select(".page, .stui-page, .section__nav").text())
            ?.groupValues?.get(2)?.toIntOrNull() ?: if (hasNext) page + 1 else page
        return PaginatedResult(unique, page, totalPages, hasNext || page < totalPages)
    }

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
            for (link in block.select(episodeSelector)) {
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
            Vod(vodId, sourceType, title, cover, category, year, status,
                siteStatus = parseEpisodeStatus(status)),
            director, actors, synopsis, sorted)
    }

    private fun resolveUrl(url: String, baseUrl: String): String = when {
        url.isBlank() -> ""
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$baseUrl$url"
        else -> url
    }

    private fun extractMeta(text: String, key: String): String =
        Regex("$key[：:]\\s*(.+?)(?=\\s+\\S+[：:]|$)").find(text)?.groupValues?.get(1)?.trim() ?: ""

    private companion object {
        val stabilityOrder = listOf("順暢", "無盡", "極速", "高清", "騰訊", "藍光", "4K", "優質", "非凡")
    }
}

/**
 * gitube.tv defines the blob as `var player_aaaa={...}` and *also* emits an alias line
 * `player_data=player_aaaa;` further down the page. Searching for "player_data=" alone
 * lands on the alias, and the next `{` is unrelated JS ~20KB later — JSONObject then throws
 * and playback dies. Requiring `{` immediately after `=` skips the alias on gitube.tv and
 * still matches gimytv.me / gimyai.tw, which use `player_data={...}` directly.
 */
private val playerVarRegex = Regex("""player_(?:data|aaaa)\s*=\s*\{""")

/** Extracts the raw player JSON blob from a play page. Brace-counted so nested `vod_data` survives. */
internal fun extractPlayerJson(html: String): String {
    val match = playerVarRegex.find(html) ?: throw ScraperException("player_data not found")
    val start = match.range.last  // the '{' itself
    var depth = 0
    for (i in start until html.length) {
        when (html[i]) {
            '{' -> depth++
            '}' -> { depth--; if (depth == 0) return html.substring(start, i + 1) }
        }
    }
    throw ScraperException("player JSON incomplete")
}
