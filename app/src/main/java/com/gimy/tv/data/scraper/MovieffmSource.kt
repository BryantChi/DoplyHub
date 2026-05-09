package com.gimy.tv.data.scraper

import com.gimy.tv.data.endpoint.EndpointResolver
import com.gimy.tv.data.local.dao.MovieffmSlugDao
import com.gimy.tv.data.local.entity.MovieffmSlugEntity
import com.gimy.tv.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import javax.inject.Inject

class MovieffmSource @Inject constructor(
    private val client: OkHttpClient,
    private val slugDao: MovieffmSlugDao,
    private val endpointResolver: EndpointResolver,
) : SiteSource {

    override val sourceType = SourceType.MOVIEFFM
    override val baseUrl: String get() = endpointResolver.getBaseUrl(sourceType)

    // Bidirectional slug <-> ID mapping (in-memory cache, backed by Room).
    // Bounded LRU (2000 each) — without the cap these maps grew forever as users
    // browse, leaking memory in long sessions. Eviction is safe: registerSlug recomputes
    // an evicted slug to the same id (deterministic hash), and `idToSlug` falls back to
    // the Room slugDao at the call site (line ~87).
    private val slugCacheCapacity = 2000
    private val slugToId: MutableMap<String, Long> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, Long>(slugCacheCapacity, 0.75f, /* accessOrder = */ true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
                    size > slugCacheCapacity
            }
        )
    private val idToSlug: MutableMap<Long, String> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<Long, String>(slugCacheCapacity, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?): Boolean =
                    size > slugCacheCapacity
            }
        )
    private val idToContentType: MutableMap<Long, String> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<Long, String>(slugCacheCapacity, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?): Boolean =
                    size > slugCacheCapacity
            }
        )

    private fun registerSlug(slug: String, contentType: String): Long {
        return slugToId.getOrPut(slug) {
            var id = slug.hashCode().toLong() and Long.MAX_VALUE
            // Handle hash collisions: if ID is taken by a different slug, increment
            while (idToSlug.containsKey(id) && idToSlug[id] != slug) {
                id = (id + 1) and Long.MAX_VALUE
            }
            idToSlug[id] = slug
            idToContentType[id] = contentType
            id
        }
    }

    // typeId → (basePath, queryParams) mapping for subcategories
    private val categoryRoutes = mapOf(
        100 to ("movies" to ""),
        101 to ("movies" to "orderby=month"),
        200 to ("drama" to ""),
        201 to ("drama" to "tvtype=krdrama"),
        202 to ("drama" to "tvtype=cndrama"),
        203 to ("drama" to "tvtype=usdrama"),
        204 to ("drama" to "tvtype=jpdrama"),
        205 to ("drama" to "tvtype=anime"),
        206 to ("drama" to "tvtype=10"),
        207 to ("drama" to "tvtype=twdrama"),
        208 to ("drama" to "tvtype=hkdrama"),
    )

    override suspend fun fetchCategories(): List<Category> = listOf(
        Category(100, "電影", sourceType),
        Category(101, "熱門電影", sourceType),
        Category(200, "電視劇", sourceType),
        Category(201, "韓劇", sourceType),
        Category(202, "陸劇", sourceType),
        Category(203, "美劇", sourceType),
        Category(204, "日劇", sourceType),
        Category(205, "動漫", sourceType),
        Category(206, "綜藝", sourceType),
        Category(207, "台劇", sourceType),
        Category(208, "港劇", sourceType),
    )

    override suspend fun fetchVodList(typeId: Int, page: Int): PaginatedResult<Vod> =
        withContext(Dispatchers.IO) {
            val (basePath, query) = categoryRoutes[typeId] ?: ("movies" to "")
            val pagePart = if (page <= 1) "" else "page/$page/"
            val queryPart = if (query.isBlank()) "" else "?$query"
            val url = "$baseUrl/$basePath/$pagePart$queryPart"
            val doc = fetchDocument(url)
            parseVodList(doc, page)
        }

    override suspend fun fetchVodDetail(vodId: Long): VodDetail =
        withContext(Dispatchers.IO) {
            val slug = idToSlug[vodId]
                ?: slugDao.getSlug(vodId)?.also { idToSlug[vodId] = it }
                ?: throw ScraperException("Unknown movieffm ID: $vodId")
            val contentType = idToContentType[vodId]
                ?: slugDao.getContentType(vodId)?.also { idToContentType[vodId] = it }
                ?: "movies"
            val doc = fetchDocument("$baseUrl/$contentType/$slug/")
            val relatedSlugs = mutableListOf<MovieffmSlugEntity>()
            val detail = parseVodDetail(doc, vodId, relatedSlugs)
            if (relatedSlugs.isNotEmpty()) slugDao.insertAll(relatedSlugs)
            detail
        }

    override suspend fun fetchPlayerData(episodeUrl: String): PlayerData {
        // movieffm URLs are direct m3u8/mp4, no encryption needed
        return PlayerData(streamUrl = episodeUrl, encrypt = 0, from = "movieffm")
    }

    override suspend fun search(keyword: String, page: Int): PaginatedResult<Vod> =
        withContext(Dispatchers.IO) {
            val enc = java.net.URLEncoder.encode(keyword, "UTF-8")
            val url = if (page <= 1) "$baseUrl/xssearch?q=$enc&f=_all"
            else "$baseUrl/xssearch?q=$enc&f=_all&p=$page"
            val doc = fetchDocument(url)
            parseSearchResults(doc, page)
        }

    // ── HTTP helpers ──

    private fun fetchHtml(url: String): String {
        val req = Request.Builder().url(url).build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ScraperException("HTTP ${resp.code}: $url")
            resp.body?.string() ?: throw ScraperException("Empty body: $url")
        }
    }

    private fun fetchDocument(url: String): Document = Jsoup.parse(fetchHtml(url), url)

    // ── List / Search parsing ──

    /**
     * Parse article.item cards from list/search pages.
     *
     * HTML structure:
     * <article class="item movies">
     *   <div class="poster">
     *     <img data-lazy-src="IMAGE_URL" alt="TITLE">
     *     <div class="rating"><span class="icon-star2"></span> 6.1</div>
     *     <div class="mepo"><span class="quality">HD</span></div>
     *     <a href="/movies/SLUG/"><div class="see"></div></a>
     *   </div>
     *   <div class="data"><h3><a href="/movies/SLUG/">TITLE</a></h3><span>2017/11/02</span></div>
     * </article>
     */
    private suspend fun parseVodList(doc: Document, page: Int): PaginatedResult<Vod> {
        val items = mutableListOf<Vod>()
        val slugEntities = mutableListOf<MovieffmSlugEntity>()

        for (card in doc.select("article.item")) {
            val linkEl = card.selectFirst("h3 a") ?: card.selectFirst(".poster a") ?: continue
            val href = linkEl.attr("abs:href").ifBlank { linkEl.attr("href") }

            // Extract content type and slug from URL: /movies/slug/ or /drama/slug/
            val match = Regex("/(movies|drama|tvshows)/([^/]+)/?$").find(href) ?: continue
            val contentType = match.groupValues[1]
            val slug = match.groupValues[2]

            val title = card.selectFirst("h3 a")?.text()?.trim() ?: continue
            if (title.isBlank()) continue

            // Image: prefer data-lazy-src over src (which is placeholder SVG)
            val cover = card.selectFirst(".poster img")?.let {
                it.attr("data-lazy-src").ifBlank { it.attr("src") }
            }?.let { resolveUrl(it) }
                ?: card.selectFirst(".poster noscript img")?.attr("src")?.let { resolveUrl(it) }
                ?: ""

            // Rating (inside .poster .rating)
            val ratingText = card.selectFirst(".rating")?.text()?.trim() ?: ""
            val rating = Regex("[\\d.]+").find(ratingText)?.value?.toDoubleOrNull()

            // Year: try .upyear, .data span, .metadata span
            val year = card.selectFirst(".upyear")?.text()?.trim()?.toIntOrNull()
                ?: Regex("\\d{4}").find(
                    card.selectFirst(".data span")?.text()
                        ?: card.selectFirst(".metadata span")?.text() ?: ""
                )?.value?.toIntOrNull() ?: 0

            // Status: drama uses .upinfo (更新至12集), movie uses .quality (HD)
            val status = card.selectFirst(".upinfo")?.text()?.trim()
                ?: card.selectFirst(".quality")?.text()?.trim() ?: ""

            // Category: drama has .dramaleixing (日劇/韓劇/...), movie uses genres
            val category = card.selectFirst(".dramaleixing")?.text()?.trim() ?: ""

            val vodId = registerSlug(slug, contentType)
            items.add(Vod(vodId, sourceType, title, cover, category, year, status, rating))
            slugEntities.add(MovieffmSlugEntity(vodId, slug, contentType))
        }

        // Persist slug mappings to Room
        if (slugEntities.isNotEmpty()) {
            slugDao.insertAll(slugEntities)
        }

        // Pagination: extract max page from pagination links
        val pagination = doc.selectFirst("div.pagination")
        val maxPage = pagination?.select("a[href*=/page/]")
            ?.mapNotNull { Regex("/page/(\\d+)").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            ?.maxOrNull()
        val totalPages = maxPage ?: page
        val hasNext = page < totalPages

        return PaginatedResult(items.distinctBy { it.id }, page, totalPages, hasNext)
    }

    // ── Search results parsing ──

    /**
     * Parse search results from /xssearch endpoint.
     * Structure:
     * <div class="result-item">
     *   <article>
     *     <div class="image"><div class="thumbnail animation-2">
     *       <a href="/tvshows/slug/"><img src="IMG"><span class="tvshows">電視劇</span></a>
     *     </div></div>
     *     <div class="details">
     *       <div class="title"><a href="/tvshows/slug/">Title</a></div>
     *       <div class="meta">
     *         <div class="dbl rating">IMDb： 8.1</div>
     *         <div class="dbl">2023</div>
     *       </div>
     *     </div>
     *   </article>
     * </div>
     */
    private suspend fun parseSearchResults(doc: Document, page: Int): PaginatedResult<Vod> {
        val items = mutableListOf<Vod>()
        val slugEntities = mutableListOf<MovieffmSlugEntity>()

        for (card in doc.select("div.result-item")) {
            // Title & link from .details .title a
            val linkEl = card.selectFirst(".details .title a")
                ?: card.selectFirst(".title a")
                ?: continue
            val href = linkEl.attr("abs:href").ifBlank { linkEl.attr("href") }

            val match = Regex("/(movies|drama|tvshows)/([^/]+)/?$").find(href) ?: continue
            val contentType = match.groupValues[1]
            val slug = match.groupValues[2]

            val title = linkEl.text().trim()
            if (title.isBlank()) continue

            // Image from .thumbnail img (direct src, not lazy-loaded)
            val cover = card.selectFirst(".thumbnail img")?.attr("src")?.let { resolveUrl(it) }
                ?: card.selectFirst("img")?.attr("src")?.let { resolveUrl(it) }
                ?: ""

            // IMDb rating from .dbl.rating
            val ratingText = card.selectFirst(".dbl.rating")?.text() ?: ""
            val rating = Regex("[\\d.]+").find(ratingText)?.value?.toDoubleOrNull()

            // Year: find .dbl that contains a 4-digit year
            val year = card.select(".meta .dbl").mapNotNull {
                Regex("^\\d{4}$").find(it.text().trim())?.value?.toIntOrNull()
            }.firstOrNull() ?: 0

            // Type badge from span inside .thumbnail (class = movies/tvshows/drama)
            val typeBadge = card.selectFirst(".thumbnail span")?.text()?.trim() ?: ""

            val vodId = registerSlug(slug, contentType)
            items.add(Vod(vodId, sourceType, title, cover, "", year, typeBadge, rating))
            slugEntities.add(MovieffmSlugEntity(vodId, slug, contentType))
        }

        // Persist slug mappings
        if (slugEntities.isNotEmpty()) {
            slugDao.insertAll(slugEntities)
        }

        // Pagination: /xssearch?q=...&p=2
        val pagination = doc.selectFirst("div.pagination")
        val maxPage = pagination?.select("a[href]")
            ?.mapNotNull { Regex("[&?]p=(\\d+)").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            ?.maxOrNull()
        val totalPages = maxPage ?: page
        val hasNext = page < totalPages

        return PaginatedResult(items.distinctBy { it.id }, page, totalPages, hasNext)
    }

    // ── Detail page parsing ──

    /**
     * Parse movie or drama detail page.
     *
     * Movie: .sheader with h1, .poster img, .sgeneros, .extra spans
     * Drama: same + .dramacbox with status/type/cast
     * Video URLs: embedded JavaScript `videourls:[...]`
     */
    private fun parseVodDetail(doc: Document, vodId: Long, relatedSlugs: MutableList<MovieffmSlugEntity>): VodDetail {
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Unknown"

        // Cover image
        val cover = doc.selectFirst(".sheader .poster img")?.let {
            it.attr("data-lazy-src").ifBlank { it.attr("src") }
        }?.let { resolveUrl(it) }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { resolveUrl(it) }
            ?: ""

        // Rating
        val rating = doc.selectFirst("span.dt_rating_vgs")?.text()?.toDoubleOrNull()

        // Genres
        val category = doc.select(".sgeneros a[rel=tag]").joinToString(", ") { it.text().trim() }

        // Year from .extra spans
        val year = doc.selectFirst(".extra")?.text()?.let {
            Regex("\\d{4}").find(it)?.value?.toIntOrNull()
        } ?: 0

        // Director & actors
        var director = ""
        var actors = listOf<String>()

        // Drama pages have .dramacbox with structured metadata
        val dramaBox = doc.selectFirst(".dramacbox")
        if (dramaBox != null) {
            for (dbl in dramaBox.select(".dbl")) {
                val text = dbl.text()
                when {
                    text.contains("主演") -> actors = dbl.select("a[rel=tag]").map { it.text().trim() }
                    text.contains("導演") -> director = dbl.select("a[rel=tag]").joinToString(", ") { it.text().trim() }
                }
            }
        }
        // Movie pages: look for person links in cast section
        if (actors.isEmpty()) {
            actors = doc.select("#cast .person a span").map { it.text().trim() }.filter { it.isNotBlank() }
        }

        // Synopsis
        val synopsis = doc.selectFirst(".wp-content p")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: ""

        // Status (drama only)
        val status = dramaBox?.select(".dbl")?.firstOrNull { it.text().contains("狀態") }
            ?.selectFirst("span")?.text()?.trim() ?: ""

        // Extract source names from DooPlay player option tabs:
        // <li @click='play(SOURCE_IDX,EP)'><span class='title'>FLV片源</span><span class='tuijian'>推薦3</span></li>
        val sourceNames = mutableMapOf<Int, String>()
        for (li in doc.select("#playeroptionsul li[class*=dooplay_player_option], li[\\@click^=play]")) {
            val click = li.attr("@click").ifBlank { li.attr("v-on:click") }
            val playMatch = Regex("play\\((\\d+)").find(click) ?: continue
            val srcIdx = playMatch.groupValues[1].toIntOrNull() ?: continue
            val tuijian = li.selectFirst(".tuijian")?.text()?.trim() ?: ""
            val titleText = li.selectFirst(".title")?.text()?.trim() ?: ""
            val name = when {
                tuijian.isNotBlank() -> tuijian
                titleText.isNotBlank() -> titleText
                else -> "線路 ${srcIdx + 1}"
            }
            sourceNames[srcIdx] = name
        }

        // Parse video URLs from embedded JavaScript
        val html = doc.html()
        val episodeGroups = parseVideoUrls(html, sourceNames)

        // Parse series and related/recommended content
        val seriesVods = parseSeriesVods(doc, relatedSlugs)
        val relatedVods = parseRelatedVods(doc, relatedSlugs)

        return VodDetail(
            Vod(vodId, sourceType, title, cover, category, year, status, rating),
            director, actors, synopsis, episodeGroups, seriesVods, relatedVods
        )
    }

    // ── Video URL parsing ──

    /**
     * Extract video URLs from embedded JavaScript.
     *
     * Two known formats:
     * 1. Flat: videourls:[{"source":0,"url":"...","type":"hls","ep":0},...]
     * 2. Nested: videourls:[[{"name":"01","url":"..."},...]...]
     */
    private fun parseVideoUrls(html: String, sourceNames: Map<Int, String> = emptyMap()): List<EpisodeGroup> {
        val idx = html.indexOf("videourls:")
        if (idx == -1) return emptyList()

        val start = html.indexOf('[', idx)
        if (start == -1) return emptyList()

        // Find matching closing bracket
        var depth = 0; var end = -1
        for (i in start until minOf(start + 500_000, html.length)) {
            when (html[i]) {
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) { end = i; break } }
            }
        }
        if (end == -1) return emptyList()

        val rawJson = html.substring(start, end + 1)

        return try {
            val arr = JSONArray(rawJson)
            if (arr.length() == 0) return emptyList()

            val first = arr.get(0)
            if (first is JSONArray) {
                parseNestedVideoUrls(arr)
            } else {
                parseFlatVideoUrls(arr, sourceNames)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Map a raw episode label to an [Episode.kind] tag. null = main-line.
     *
     * Used by parseNestedVideoUrls so watch-history can keep "OAD 5" / "番外1" /
     * "特別篇" progress separate from regular ep5. Pattern matching only on the
     * label string — we don't try to reach into the JSON for a `type` field
     * because MovieFFM's payload doesn't expose one consistently.
     */
    private fun detectEpisodeKind(label: String): String? {
        val t = label.lowercase()
        return when {
            t.contains("oad") || t.contains("ova") -> "OAD"
            label.contains("番外") -> "番外"
            label.contains("特別篇") || t.contains("special") -> "特別篇"
            label.contains("劇場版") -> "劇場版"
            label.contains("外傳") -> "外傳"
            else -> null
        }
    }

    /**
     * Format: [{source:0, url:"...", type:"hls", ep:0}, ...]
     * Group by source, each group = one EpisodeGroup.
     */
    private fun parseFlatVideoUrls(arr: JSONArray, sourceNames: Map<Int, String> = emptyMap()): List<EpisodeGroup> {
        data class Entry(val source: Int, val url: String, val ep: Int)

        val entries = mutableListOf<Entry>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val url = obj.optString("url", "").replace("\\/", "/")
            val type = obj.optString("type", "")
            if (url.isBlank() || type == "iframe") continue
            entries.add(Entry(obj.optInt("source", 0), url, obj.optInt("ep", 0)))
        }

        return entries.groupBy { it.source }
            .map { (sourceIdx, items) ->
                val episodes = items.map { entry ->
                    val epNum = if (entry.ep == 0) 1 else entry.ep
                    Episode(epNum, "第${epNum}集", entry.url)
                }.sortedBy { it.number }.distinctBy { it.number }

                val name = sourceNames[sourceIdx] ?: "線路 ${sourceIdx + 1}"
                EpisodeGroup(name, sourceIdx + 1000, episodes)
            }
    }

    /**
     * Format: [[{name:"01", url:"..."}, ...], [...]]
     * Each inner array = one source/EpisodeGroup.
     */
    private fun parseNestedVideoUrls(arr: JSONArray): List<EpisodeGroup> {
        val groups = mutableListOf<EpisodeGroup>()
        for (i in 0 until arr.length()) {
            val innerArr = arr.optJSONArray(i) ?: continue
            val episodes = mutableListOf<Episode>()
            for (j in 0 until innerArr.length()) {
                val obj = innerArr.optJSONObject(j) ?: continue
                val url = obj.optString("url", "").replace("\\/", "/")
                if (url.isBlank()) continue
                val nameRaw = obj.optString("name", "${j + 1}")
                val epNum = Regex("\\d+").find(nameRaw)?.value?.toIntOrNull() ?: (j + 1)
                // Preserve named-arc labels ("OAD"/"番外篇 1"/"特別篇") instead of
                // overwriting them with "第N集". When name is just a number ("01" /
                // "  3 ") we still normalize to the standard "第N集" form for
                // consistency with parseFlatVideoUrls.
                val displayTitle = if (Regex("^\\s*\\d+\\s*\$").matches(nameRaw)) {
                    "第${epNum}集"
                } else {
                    nameRaw
                }
                val kind = detectEpisodeKind(nameRaw)
                episodes.add(Episode(epNum, displayTitle, url, kind = kind))
            }
            if (episodes.isNotEmpty()) {
                groups.add(EpisodeGroup("線路 ${i + 1}", i + 1000, episodes.sortedBy { it.number }))
            }
        }
        return groups
    }

    // ── Related/recommended content ──

    /**
     * Parse related vods from #single_relacionados_b section.
     * <article><a href="/movies/slug/"><img alt="Title" data-lazy-src="IMG"></a></article>
     */
    private fun parseRelatedVods(doc: Document, outSlugs: MutableList<MovieffmSlugEntity>): List<Vod> {
        val container = doc.selectFirst("#single_relacionados_b") ?: return emptyList()
        val items = mutableListOf<Vod>()

        for (article in container.select("article")) {
            val link = article.selectFirst("a[href]") ?: continue
            val href = link.attr("abs:href").ifBlank { link.attr("href") }
            val match = Regex("/(movies|drama|tvshows)/([^/]+)/?$").find(href) ?: continue
            val contentType = match.groupValues[1]
            val slug = match.groupValues[2]

            val img = article.selectFirst("img") ?: continue
            val title = img.attr("alt").trim()
            if (title.isBlank()) continue
            val cover = img.attr("data-lazy-src").ifBlank { img.attr("src") }.let { resolveUrl(it) }

            val vodId = registerSlug(slug, contentType)
            items.add(Vod(vodId, sourceType, title, cover, "", 0, ""))
            outSlugs.add(MovieffmSlugEntity(vodId, slug, contentType))
        }
        return items
    }

    /**
     * Parse series vods from the series section on detail pages.
     * Tries #single_relacionados_a, .sbox:has(h2:contains(series)), .sbox:has(h2:contains(Serie)).
     * Uses the same article-parsing logic as parseRelatedVods.
     */
    private fun parseSeriesVods(doc: Document, outSlugs: MutableList<MovieffmSlugEntity>): List<Vod> {
        val container = doc.selectFirst("#single_relacionados_a")
            ?: doc.selectFirst(".sbox:has(h2:contains(series))")
            ?: doc.selectFirst(".sbox:has(h2:contains(Serie))")
            ?: return emptyList()
        val items = mutableListOf<Vod>()

        for (article in container.select("article")) {
            val link = article.selectFirst("a[href]") ?: continue
            val href = link.attr("abs:href").ifBlank { link.attr("href") }
            val match = Regex("/(movies|drama|tvshows)/([^/]+)/?$").find(href) ?: continue
            val contentType = match.groupValues[1]
            val slug = match.groupValues[2]

            val img = article.selectFirst("img") ?: continue
            val title = img.attr("alt").trim()
            if (title.isBlank()) continue
            val cover = img.attr("data-lazy-src").ifBlank { img.attr("src") }.let { resolveUrl(it) }

            val vodId = registerSlug(slug, contentType)
            items.add(Vod(vodId, sourceType, title, cover, "", 0, ""))
            outSlugs.add(MovieffmSlugEntity(vodId, slug, contentType))
        }
        return items
    }

    private fun resolveUrl(url: String): String = when {
        url.isBlank() || url.startsWith("data:") -> ""
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$baseUrl$url"
        else -> url
    }
}
