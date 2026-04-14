package com.gimy.tv.data.scraper

import com.gimy.tv.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import javax.inject.Inject

class GimyMaxSource @Inject constructor(
    private val client: OkHttpClient
) : SiteSource {

    override val sourceType = SourceType.GIMYMAX
    override val baseUrl = "https://gimymax.com"

    // Stability ranking: first = most stable (user-confirmed preference)
    private val stabilityOrder = listOf("無盡", "順暢", "極速", "高清", "騰訊", "藍光", "4K", "優質", "非凡")

    override suspend fun fetchCategories(): List<Category> = listOf(
        Category(2, "電視劇", sourceType), Category(1, "電影", sourceType),
        Category(4, "動漫", sourceType), Category(29, "綜藝", sourceType),
        Category(13, "陸劇", sourceType), Category(20, "韓劇", sourceType),
        Category(16, "美劇", sourceType), Category(21, "日劇", sourceType),
        Category(14, "台劇", sourceType), Category(15, "港劇", sourceType),
        Category(30, "紀錄片", sourceType),
    )

    override suspend fun fetchVodList(typeId: Int, page: Int): PaginatedResult<Vod> =
        withContext(Dispatchers.IO) {
            // Correct pagination: /type/ID-PAGE.html (e.g. /type/20-2.html)
            val url = if (page <= 1) "$baseUrl/type/$typeId.html"
                else "$baseUrl/type/$typeId-$page.html"
            parseVodList(fetchDocument(url), page)
        }

    override suspend fun fetchVodDetail(vodId: Long): VodDetail =
        withContext(Dispatchers.IO) {
            parseVodDetail(fetchDocument("$baseUrl/vod/$vodId.html"), vodId)
        }

    override suspend fun fetchPlayerData(episodeUrl: String): PlayerData =
        withContext(Dispatchers.IO) {
            val url = if (episodeUrl.startsWith("http")) episodeUrl else "$baseUrl$episodeUrl"
            parsePlayerData(fetchHtml(url))
        }

    override suspend fun search(keyword: String, page: Int): PaginatedResult<Vod> =
        withContext(Dispatchers.IO) {
            val enc = java.net.URLEncoder.encode(keyword, "UTF-8")
            val doc = fetchDocument("$baseUrl/search/$enc----------$page---.html")
            parseSearchResults(doc, page)
        }

    /**
     * Parse search results. Same a.video-pic structure as list pages,
     * but must exclude #stickyside ranking sidebar.
     */
    private fun parseSearchResults(doc: Document, page: Int): PaginatedResult<Vod> {
        // Remove ranking sidebar
        doc.select("#stickyside").remove()

        // Use the same parser as list pages — search results use identical HTML structure
        return parseVodList(doc, page)
    }

    private fun fetchHtml(url: String): String {
        val req = Request.Builder().url(url).build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ScraperException("HTTP ${resp.code}: $url")
            resp.body?.string() ?: throw ScraperException("Empty body: $url")
        }
    }

    private fun fetchDocument(url: String): Document = Jsoup.parse(fetchHtml(url), url)

    // ── List page ──

    private fun parseVodList(doc: Document, page: Int): PaginatedResult<Vod> {
        val items = mutableListOf<Vod>()
        // Cards: <a class="video-pic loading" href="/vod/ID.html" title="TITLE" data-original="IMG">
        for (card in doc.select("a[class*=video-pic][data-original]")) {
            if (!card.attr("href").contains("/vod/")) continue
            val id = Regex("/vod/(\\d+)\\.html").find(card.attr("href"))
                ?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val title = card.attr("title").trim()
            if (title.isBlank()) continue
            val cover = resolveUrl(card.attr("data-original"))
            val status = card.selectFirst("span.note")?.text()?.trim() ?: ""
            items.add(Vod(id, sourceType, title, cover, "", 0, status))
        }
        val unique = items.distinctBy { it.id }

        // Pagination: check for next page link, and parse "1/31" total pages
        val hasNext = doc.select("a:contains(下一頁), a.next, a[title=下一頁]").isNotEmpty()
        val totalPages = Regex("(\\d+)/(\\d+)").find(doc.select(".page, .stui-page, .pagination").text())
            ?.groupValues?.get(2)?.toIntOrNull() ?: if (hasNext) page + 1 else page
        return PaginatedResult(unique, page, totalPages, hasNext || page < totalPages)
    }

    // ── Detail page ──

    private fun parseVodDetail(doc: Document, vodId: Long): VodDetail {
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.let { resolveUrl(it) } ?: ""

        val body = doc.body().text()
        val director = extractMeta(body, "導演")
        val actors = extractMeta(body, "主演")
            .split(Regex("[,，/、]")).map { it.trim() }.filter { it.isNotBlank() }
        val year = (extractMeta(body, "年代") + extractMeta(body, "年份"))
            .filter { it.isDigit() }.take(4).toIntOrNull() ?: 0
        val category = extractMeta(body, "類型").ifBlank { extractMeta(body, "分類") }
        val status = extractMeta(body, "狀態")
        val synopsis = doc.select("p").firstOrNull {
            it.text().length > 50 && !it.text().contains("導演")
        }?.text()?.trim() ?: ""

        // Parse episodes from div.playlist-mobile containers
        val groups = parsePlaylistMobile(doc)

        // Sort by stability
        val sorted = groups.sortedWith(compareByDescending { group ->
            val idx = stabilityOrder.indexOfFirst { group.sourceName.contains(it) }
            if (idx >= 0) stabilityOrder.size - idx else -1
        })

        // Parse related/recommended content from 熱門推薦 section
        val relatedVods = parseRelatedVods(doc)

        return VodDetail(
            Vod(vodId, sourceType, title, cover, category, year, status),
            director, actors, synopsis, sorted, relatedVods
        )
    }

    /**
     * Parse related vods from the 熱播{Category} section (same-category content).
     * Falls back to 熱門推薦 if no category-specific section found.
     * <a class="video-pic" href="/vod/ID.html" title="TITLE" data-background="IMG">
     */
    private fun parseRelatedVods(doc: Document): List<Vod> {
        // Prefer 熱播{X} (same-category), fallback to 熱門推薦
        val container = doc.selectFirst(".box-title:has(h3:matches(^熱播))")?.parent()
            ?: doc.selectFirst(".box-title:has(h3:contains(推薦))")?.parent()
            ?: return emptyList()
        val items = mutableListOf<Vod>()
        for (card in container.select("a[class*=video-pic][data-background]")) {
            val href = card.attr("href")
            val id = Regex("/vod/(\\d+)\\.html").find(href)
                ?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val cardTitle = card.attr("title").trim()
            if (cardTitle.isBlank()) continue
            val cardCover = resolveUrl(card.attr("data-background"))
            val cardStatus = card.selectFirst("span.note")?.text()?.trim() ?: ""
            items.add(Vod(id, sourceType, cardTitle, cardCover, "", 0, cardStatus))
        }
        return items
    }

    /**
     * Parse episode groups from the actual HTML structure:
     * <div class="playlist-mobile playlist layout-box clearfix">
     *   <li><div class="gico dyttm3u8">高清線路 ᴴᴰ</div></li>
     *   <ul id="con_playlist_1" class="clearfix fade in active">
     *     <li><a href="/ep/388-1-1.html">第1集</a></li>
     *   </ul>
     * </div>
     */
    private fun parsePlaylistMobile(doc: Document): List<EpisodeGroup> {
        val groups = mutableListOf<EpisodeGroup>()

        for (container in doc.select("div.playlist-mobile.playlist")) {
            // Source name from div.gico
            val gicoDiv = container.selectFirst("div.gico") ?: continue
            val sourceName = gicoDiv.text().trim()
                .replace(" ᴴᴰ", "").replace("ᴴᴰ", "").trim()

            // Episodes from ul
            val ul = container.selectFirst("ul[id^=con_playlist_]") ?: continue
            val episodes = mutableListOf<Episode>()
            var sourceId = 0

            for (link in ul.select("a[href~=/ep/]")) {
                val match = Regex("/ep/\\d+-(\\d+)-(\\d+)\\.html").find(link.attr("href")) ?: continue
                sourceId = match.groupValues[1].toIntOrNull() ?: continue
                val epNum = match.groupValues[2].toIntOrNull() ?: continue
                episodes.add(Episode(epNum, link.text().trim(), link.attr("href")))
            }

            if (episodes.isNotEmpty() && sourceName.isNotBlank()) {
                groups.add(EpisodeGroup(sourceName, sourceId, episodes.sortedBy { it.number }))
            }
        }

        // Fallback if no playlist-mobile containers
        if (groups.isEmpty()) {
            val bySource = mutableMapOf<Int, MutableList<Episode>>()
            for (link in doc.select("a[href~=/ep/\\d+-\\d+-\\d+\\.html]")) {
                val m = Regex("/ep/\\d+-(\\d+)-(\\d+)\\.html").find(link.attr("href")) ?: continue
                val sId = m.groupValues[1].toIntOrNull() ?: continue
                val ep = m.groupValues[2].toIntOrNull() ?: continue
                bySource.getOrPut(sId) { mutableListOf() }
                    .add(Episode(ep, link.text().trim(), link.attr("href")))
            }
            for ((sId, eps) in bySource) {
                groups.add(EpisodeGroup("線路 $sId", sId, eps.sortedBy { it.number }))
            }
        }

        return groups
    }

    // ── Player page ──

    private fun parsePlayerData(html: String): PlayerData {
        val idx = html.indexOf("player_data=")
        if (idx == -1) throw ScraperException("player_data not found")
        val start = html.indexOf('{', idx)
        if (start == -1) throw ScraperException("JSON start not found")

        var depth = 0; var end = -1
        for (i in start until minOf(start + 5000, html.length)) {
            when (html[i]) { '{' -> depth++; '}' -> { depth--; if (depth == 0) { end = i; break } } }
        }
        if (end == -1) throw ScraperException("JSON end not found")

        val json = JSONObject(html.substring(start, end + 1))
        val encrypt = json.optInt("encrypt", 0)
        var url = json.optString("url", "")
        val from = json.optString("from", "")

        url = when (encrypt) {
            0 -> url
            1 -> String(android.util.Base64.decode(url, android.util.Base64.DEFAULT))
            2 -> { val f = String(android.util.Base64.decode(url, android.util.Base64.DEFAULT))
                String(android.util.Base64.decode(f, android.util.Base64.DEFAULT)) }
            else -> url
        }
        if (url.isBlank()) throw ScraperException("Empty stream URL")
        return PlayerData(url, encrypt, from)
    }

    private fun resolveUrl(url: String): String = when {
        url.isBlank() -> ""; url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$baseUrl$url"; else -> url
    }

    private fun extractMeta(text: String, key: String): String {
        return Regex("$key[：:]\\s*(.+?)(?=\\s+\\S+[：:]|$)")
            .find(text)?.groupValues?.get(1)?.trim() ?: ""
    }
}

class ScraperException(message: String) : Exception(message)
