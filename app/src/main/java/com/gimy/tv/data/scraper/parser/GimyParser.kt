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
/**
 * Which template a Gimy mirror serves.
 *
 * [CARD] — gimytv.me / gitube.tv: `a.card__thumb` cards, `div.playlist-block` routes.
 * [POSTER] — gimyai.tw: `a.poster` cards, `div.block` + `.route-title` routes.
 *
 * Both encode the route id the same way in episode URLs (`/{token}/{vod}-{sid}-{ep}.html`),
 * so only the selectors differ — everything downstream is shared.
 */
enum class GimyLayout { CARD, POSTER }

class GimyParser(
    private val sourceType: SourceType,
    private val paths: GimyPaths,
    private val layout: GimyLayout = GimyLayout.CARD,
) {
    private val vodIdRegex = Regex("${Regex.escape(paths.detail)}/(\\d+)\\.html")
    private val epRegex = Regex("${Regex.escape(paths.episode)}/\\d+-(\\d+)-(\\d+)\\.html")
    private val cardSelector = when (layout) {
        GimyLayout.CARD -> "a.card__thumb[href*=${paths.detail}/]"
        GimyLayout.POSTER -> "a.poster[href*=${paths.detail}/]"
    }
    private val statusSelector = when (layout) {
        GimyLayout.CARD -> "span.card__badge"
        GimyLayout.POSTER -> "span.poster__status"
    }
    private val routeBlockSelector = when (layout) {
        GimyLayout.CARD -> "div.playlist-block"
        GimyLayout.POSTER -> "div.block"
    }
    private val routeTitleSelector = when (layout) {
        GimyLayout.CARD -> ".playlist-block__title"
        GimyLayout.POSTER -> ".route-title"
    }
    private val episodeSelector = "a[href~=${paths.episode}/]"
    private val searchCardSelector = "a.search-item__thumb[href*=${paths.detail}/]"

    fun parseVodList(doc: Document, baseUrl: String, page: Int): PaginatedResult<Vod> {
        doc.select("#stickyside, .rankings, .rank-list").remove()
        val items = mutableListOf<Vod>()
        for (card in doc.select(cardSelector)) {
            val id = vodIdRegex.find(card.attr("href"))?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val title = card.attr("aria-label")
                .ifBlank { card.selectFirst("h3.poster__title")?.text().orEmpty() }
                .ifBlank { card.selectFirst("img")?.attr("alt").orEmpty() }
                .ifBlank { card.parent()?.selectFirst("a.card__body h3.card__title")?.text().orEmpty() }
                .trim()
            if (title.isBlank()) continue
            val cover = resolveUrl(card.selectFirst("img")?.attr("src").orEmpty(), baseUrl)
            val status = card.selectFirst(statusSelector)?.text()?.trim() ?: ""
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

    /**
     * Search pages ship a different template from list pages — `article.search-item` with
     * `search-item__thumb/__title/__meta` instead of the `card__*` family. Reusing
     * [parseVodList] here silently yields zero results, which is how search stayed broken
     * behind the Cloudflare wall without anyone noticing.
     *
     * Meta reads "類型 · 年份 · 地區 · 狀態"; only the trailing segment is the episode status.
     */
    fun parseSearchResults(doc: Document, baseUrl: String, page: Int): PaginatedResult<Vod> {
        doc.select("#stickyside, .rankings, .rank-list").remove()
        val items = mutableListOf<Vod>()
        for (thumb in doc.select(searchCardSelector)) {
            val id = vodIdRegex.find(thumb.attr("href"))?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val card = thumb.parents().firstOrNull { it.hasClass("search-item") }
            val title = thumb.attr("aria-label")
                .ifBlank { card?.selectFirst("h2.search-item__title")?.text().orEmpty() }
                .ifBlank { thumb.selectFirst("img")?.attr("alt").orEmpty() }
                .trim()
            if (title.isBlank()) continue
            val cover = resolveUrl(thumb.selectFirst("img")?.attr("src").orEmpty(), baseUrl)
            val status = card?.selectFirst("p.search-item__meta")?.text()
                ?.substringAfterLast('\u00B7')?.trim().orEmpty()
            @Suppress("DEPRECATION")
            items.add(Vod(id, sourceType, title, cover, "", 0, status,
                siteStatus = parseEpisodeStatus(status)))
        }
        val unique = items.distinctBy { it.id }
        val hasNext = doc.select("a:contains(下一頁), a[title=下一頁], .chip-nav--next").isNotEmpty()
        return PaginatedResult(unique, page, if (hasNext) page + 1 else page, hasNext)
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

        val groups = when (layout) {
            GimyLayout.CARD -> parseCardRoutes(doc)
            GimyLayout.POSTER -> parsePosterRoutes(doc)
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

    /** CARD: each route is its own block, and the block's title names it. */
    private fun parseCardRoutes(doc: Document): List<EpisodeGroup> {
        val groups = mutableListOf<EpisodeGroup>()
        for (block in doc.select(routeBlockSelector)) {
            val name = cleanRouteName(block.selectFirst(routeTitleSelector)?.ownText()) ?: continue
            var sId = 0
            val eps = mutableListOf<Episode>()
            for (link in block.select(episodeSelector)) {
                val m = epRegex.find(link.attr("href")) ?: continue
                sId = m.groupValues[1].toIntOrNull() ?: continue
                val n = m.groupValues[2].toIntOrNull() ?: continue
                eps.add(Episode(n, link.text().trim(), link.attr("href")))
            }
            if (eps.isNotEmpty()) groups.add(EpisodeGroup(name, sId, eps.sortedBy { it.number }))
        }
        return groups
    }

    /**
     * POSTER: every route sits inside a single block as a `.route-title` followed by its
     * `.eps[data-route-sid]` sibling. Iterating blocks here would collapse all routes into
     * one group carrying every episode, so the episode container is the anchor instead.
     */
    private fun parsePosterRoutes(doc: Document): List<EpisodeGroup> {
        val groups = mutableListOf<EpisodeGroup>()
        for (eps in doc.select(".eps[data-route-sid]")) {
            val sId = eps.attr("data-route-sid").toIntOrNull() ?: continue
            val title = eps.previousElementSibling()
                ?.takeIf { it.hasClass("route-title") }?.ownText()
            val name = cleanRouteName(title) ?: continue
            val list = eps.select(episodeSelector).mapNotNull { link ->
                val m = epRegex.find(link.attr("href")) ?: return@mapNotNull null
                val n = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                Episode(n, link.text().trim(), link.attr("href"))
            }
            if (list.isNotEmpty()) groups.add(EpisodeGroup(name, sId, list.sortedBy { it.number }))
        }
        return groups
    }

    /** ownText() already skips the nested HD badge; this drops the superscript marker too. */
    private fun cleanRouteName(raw: String?): String? =
        raw?.trim()?.replace(Regex("\\s*ᴴᴰ\\s*"), "")?.trim()?.takeIf { it.isNotBlank() }

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
