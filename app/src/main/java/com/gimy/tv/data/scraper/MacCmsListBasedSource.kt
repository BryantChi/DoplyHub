package com.gimy.tv.data.scraper

import com.gimy.tv.data.endpoint.EndpointResolver
import com.gimy.tv.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * Base class for MacCMS V10 myui-template sites where:
 *  - Episode lists are HTML <li><a href="{playPath}/{vodId}-{line}-{ep}.html"> elements
 *    (not embedded as `var player_data` JSON like GimyMax/GimyTv)
 *  - Each play page contains `var player_aaaa = {url, from, encrypt, ...}` with the m3u8
 *  - Lines are addressed by Chinese display name on the detail page (卧龍雲/索尼雲/...)
 *
 * Subclasses provide: sourceType, detailUrlPath, playUrlPath. Search/list URL builders
 * default to MacCMS V10 conventions but can be overridden per-site.
 */
abstract class MacCmsListBasedSource(
    protected val client: OkHttpClient,
    protected val endpointResolver: EndpointResolver,
) : SiteSource {

    abstract override val sourceType: SourceType
    override val baseUrl: String get() = endpointResolver.getBaseUrl(sourceType)

    /** Detail URL path: "/voddetail" (gimy.tw/eynytv/imaple) or "/vod" (momovod/123kubo). */
    abstract val detailUrlPath: String

    /** Play URL path: "/vodplay" (gimy.tw/eynytv/imaple) or "/play" (momovod/123kubo). */
    abstract val playUrlPath: String

    /** Per-source typeId table. Default falls back to enum extension. */
    open val categoryMap: SiteCategoryMap get() = sourceType.categoryMap

    /** List page URL. Default: MacCMS V10 vodshow format. */
    protected open fun buildListUrl(typeId: Int, page: Int): String =
        "$baseUrl/vodshow/$typeId--------$page---.html"

    /** Search URL. Default: form GET style with wd query param. */
    protected open fun buildSearchUrl(keyword: String, page: Int): String {
        val enc = URLEncoder.encode(keyword, "UTF-8")
        return "$baseUrl/vodsearch/-------------.html?wd=$enc"
    }

    /** Stability order by Chinese line name. Lower index = more stable (preferred). */
    protected open val stabilityOrder: List<String> =
        listOf("卧龍雲", "索尼雲", "無盡雲", "閃電雲", "極速雲", "優質雲")

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 10) Mobile Safari/537.36"

    // ─── SiteSource impl ───

    override suspend fun fetchCategories(): List<Category> {
        val m = categoryMap
        return listOf(
            m.movie to "電影",
            m.series to "劇集",
            m.anime to "動漫",
            m.variety to "綜藝",
            m.korean to "韓劇",
            m.chinese to "陸劇",
            m.hk to "港劇",
            m.taiwan to "台劇",
            m.japanese to "日劇",
            m.american to "美劇",
            m.documentary to "紀錄片",
        )
            .filter { it.first > 0 }
            .map { (id, name) -> Category(id, name, sourceType) }
    }

    override suspend fun fetchVodList(typeId: Int, page: Int): PaginatedResult<Vod> =
        withContext(Dispatchers.IO) {
            parseVodList(fetchDocument(buildListUrl(typeId, page)), page)
        }

    override suspend fun fetchVodDetail(vodId: Long): VodDetail =
        withContext(Dispatchers.IO) {
            parseVodDetail(fetchDocument("$baseUrl$detailUrlPath/$vodId.html"), vodId)
        }

    override suspend fun fetchPlayerData(episodeUrl: String): PlayerData =
        withContext(Dispatchers.IO) {
            val url = if (episodeUrl.startsWith("http")) episodeUrl else "$baseUrl$episodeUrl"
            parsePlayerAaaa(fetchHtml(url))
        }

    override suspend fun search(keyword: String, page: Int): PaginatedResult<Vod> =
        withContext(Dispatchers.IO) {
            parseVodList(fetchDocument(buildSearchUrl(keyword, page)), page)
        }

    // ─── HTTP ───

    protected fun fetchHtml(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .build()
        return client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ScraperException("HTTP ${r.code}: $url")
            r.body?.string() ?: throw ScraperException("Empty body: $url")
        }
    }

    protected fun fetchDocument(url: String): Document = Jsoup.parse(fetchHtml(url), url)

    protected fun resolveUrl(url: String): String = when {
        url.isBlank() -> ""
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$baseUrl$url"
        else -> url
    }

    // ─── List parsing ───

    /** Cards: <a class="myui-vodlist__thumb lazyload" href="{detailPath}/{id}.html" title="..." data-original="..."> */
    protected open fun parseVodList(doc: Document, page: Int): PaginatedResult<Vod> {
        val items = mutableListOf<Vod>()
        val detailHrefRegex = Regex("$detailUrlPath/(\\d+)\\.html")
        for (card in doc.select("a.myui-vodlist__thumb, a[class*=video-pic][data-original]")) {
            val href = card.attr("href")
            val id = detailHrefRegex.find(href)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val title = card.attr("title").trim().ifBlank {
                card.parent()?.selectFirst(".myui-vodlist__detail h4 a")?.text()?.trim().orEmpty()
            }
            if (title.isBlank()) continue
            val cover = resolveUrl(card.attr("data-original").ifBlank { card.attr("data-src") })
            val status = card.selectFirst("span.pic-text, span.note")?.text()?.trim() ?: ""
            items.add(Vod(id, sourceType, title, cover, "", 0, status))
        }
        val unique = items.distinctBy { it.id }
        val hasNext = doc.select("a:contains(下一頁), a:contains(下一页), a.next, a[title=下一頁]")
            .isNotEmpty()
        val totalPages = Regex("(\\d+)/(\\d+)")
            .find(doc.select(".myui-page, .stui-page, .pagination, .page").text())
            ?.groupValues?.get(2)?.toIntOrNull()
            ?: if (hasNext) page + 1 else page
        return PaginatedResult(unique, page, totalPages, hasNext || page < totalPages)
    }

    // ─── Detail parsing ───

    protected open fun parseVodDetail(doc: Document, vodId: Long): VodDetail {
        val title = doc.selectFirst("h1.title, h1")?.text()?.trim() ?: "Unknown"
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.let { resolveUrl(it) }
            ?: doc.selectFirst(".myui-content__thumb img, .module-item-pic img")
                ?.let { resolveUrl(it.attr("data-original").ifBlank { it.attr("src") }) }
            ?: ""

        val body = doc.body().text()
        val director = extractMeta(body, "導演")
        val actors = extractMeta(body, "主演")
            .split(Regex("[,，/、]")).map { it.trim() }.filter { it.isNotBlank() }
        val year = (extractMeta(body, "年代") + extractMeta(body, "年份"))
            .filter { it.isDigit() }.take(4).toIntOrNull() ?: 0
        val category = extractMeta(body, "類型").ifBlank { extractMeta(body, "分類") }
            .ifBlank { extractMeta(body, "地區") }
        val status = extractMeta(body, "狀態").ifBlank { extractMeta(body, "備註") }
        val synopsis = extractMeta(body, "簡介").ifBlank {
            doc.select(".content-desc, .video-description, .module-info-introduction p, p")
                .firstOrNull { it.text().length > 50 && !it.text().contains("導演") }
                ?.text()?.trim() ?: ""
        }

        val groups = parseEpisodeGroups(doc)
        val sorted = groups.sortedWith(compareByDescending { g ->
            val idx = stabilityOrder.indexOfFirst { g.sourceName.contains(it) }
            if (idx >= 0) stabilityOrder.size - idx else -1
        })

        return VodDetail(
            Vod(vodId, sourceType, title, cover, category, year, status),
            director, actors, synopsis, sorted
        )
    }

    /**
     * Episodes structure (myui template):
     *   <a href="#playlistN" data-toggle="tab">線路中文名</a>     ← line tab
     *   <div id="playlistN">
     *     <ul><li><a href="{playUrlPath}/{vodId}-{lineIdx}-{epIdx}.html">第N集</a></li>...</ul>
     *   </div>
     */
    protected open fun parseEpisodeGroups(doc: Document): List<EpisodeGroup> {
        val groups = mutableListOf<EpisodeGroup>()
        val tabIdRegex = Regex("#playlist(\\d+)")
        val playLinkRegex = Regex("$playUrlPath/\\d+-(\\d+)-(\\d+)\\.html")

        val tabs = doc.select("a[data-toggle=tab][href^=#playlist]")
        for (tab in tabs) {
            val tabId = tabIdRegex.find(tab.attr("href"))?.groupValues?.get(1) ?: continue
            val name = tab.text().trim()
                .replace(Regex("\\s*ᴴᴰ\\s*"), "")
                .replace(Regex("\\s+"), "")
            if (name.isBlank()) continue
            val container = doc.getElementById("playlist$tabId") ?: continue
            val episodes = mutableListOf<Episode>()
            var sourceId = 0
            for (link in container.select("a[href*=$playUrlPath/]")) {
                val m = playLinkRegex.find(link.attr("href")) ?: continue
                sourceId = m.groupValues[1].toIntOrNull() ?: continue
                val epNum = m.groupValues[2].toIntOrNull() ?: continue
                episodes.add(Episode(epNum, link.text().trim(), link.attr("href")))
            }
            if (episodes.isNotEmpty()) {
                groups.add(EpisodeGroup(name, sourceId, episodes.sortedBy { it.number }))
            }
        }

        // Fallback: bucket all play links by sourceId
        if (groups.isEmpty()) {
            val bySource = mutableMapOf<Int, MutableList<Episode>>()
            for (link in doc.select("a[href*=$playUrlPath/]")) {
                val m = playLinkRegex.find(link.attr("href")) ?: continue
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

    // ─── Player page parsing ───

    /** Extract m3u8 URL from `var player_aaaa = {url, encrypt, from, ...}`. */
    protected fun parsePlayerAaaa(html: String): PlayerData {
        val idx = html.indexOf("player_aaaa")
        if (idx == -1) throw ScraperException("player_aaaa not found")
        val start = html.indexOf('{', idx)
        if (start == -1) throw ScraperException("JSON start not found")
        var depth = 0
        var end = -1
        for (i in start until minOf(start + 5000, html.length)) {
            when (html[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { end = i; break } }
            }
        }
        if (end == -1) throw ScraperException("JSON end not found")
        val json = JSONObject(html.substring(start, end + 1))
        val encrypt = json.optInt("encrypt", 0)
        var url = json.optString("url", "")
        val from = json.optString("from", "")
        url = when (encrypt) {
            0 -> url
            1 -> String(android.util.Base64.decode(url, android.util.Base64.DEFAULT))
            2 -> {
                val first = String(android.util.Base64.decode(url, android.util.Base64.DEFAULT))
                String(android.util.Base64.decode(first, android.util.Base64.DEFAULT))
            }
            else -> url
        }
        if (url.isBlank()) throw ScraperException("Empty stream URL")
        return PlayerData(url, encrypt, from)
    }

    // ─── Helpers ───

    private fun extractMeta(text: String, key: String): String =
        Regex("$key[：:]\\s*(.+?)(?=\\s+\\S+[：:]|$)")
            .find(text)?.groupValues?.get(1)?.trim() ?: ""
}
