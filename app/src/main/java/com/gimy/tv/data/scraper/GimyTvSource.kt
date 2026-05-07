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
import javax.inject.Inject

class GimyTvSource @Inject constructor(
    private val client: OkHttpClient,
    private val endpointResolver: EndpointResolver,
) : SiteSource {

    override val sourceType = SourceType.GIMYTV
    override val baseUrl: String get() = endpointResolver.getBaseUrl(sourceType)
    private val stabilityOrder = listOf("順暢", "無盡", "極速", "高清", "騰訊", "藍光", "4K", "優質", "非凡")

    override suspend fun fetchCategories(): List<Category> = listOf(
        Category(2, "電視劇", sourceType), Category(1, "電影", sourceType),
        Category(4, "動漫", sourceType), Category(29, "綜藝", sourceType),
        Category(13, "陸劇", sourceType), Category(20, "韓劇", sourceType),
        Category(16, "美劇", sourceType), Category(21, "日劇", sourceType),
        Category(14, "台劇", sourceType), Category(15, "港劇", sourceType),
        Category(3, "紀錄片", sourceType),
    )

    override suspend fun fetchVodList(typeId: Int, page: Int): PaginatedResult<Vod> =
        withContext(Dispatchers.IO) {
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

    private fun parseSearchResults(doc: Document, page: Int): PaginatedResult<Vod> {
        doc.select("#stickyside").remove()
        return parseVodList(doc, page)
    }

    private fun fetchHtml(url: String): String {
        val req = Request.Builder().url(url).build()
        return client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ScraperException("HTTP ${r.code}: $url")
            r.body?.string() ?: throw ScraperException("Empty: $url")
        }
    }

    private fun fetchDocument(url: String): Document = Jsoup.parse(fetchHtml(url), url)

    private fun parseVodList(doc: Document, page: Int): PaginatedResult<Vod> {
        // Remove ranking sidebar
        doc.select("#stickyside, .rankings, .rank-list").remove()
        val items = mutableListOf<Vod>()
        for (card in doc.select("a[class*=video-pic][data-original]")) {
            if (!card.attr("href").contains("/vod/")) continue
            val id = Regex("/vod/(\\d+)\\.html").find(card.attr("href"))
                ?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val title = card.attr("title").trim(); if (title.isBlank()) continue
            items.add(Vod(id, sourceType, title, resolveUrl(card.attr("data-original")),
                "", 0, card.selectFirst("span.note")?.text()?.trim() ?: ""))
        }
        val unique = items.distinctBy { it.id }
        val hasNext = doc.select("a:contains(下一頁), a[title=下一頁]").isNotEmpty()
        val totalPages = Regex("(\\d+)/(\\d+)").find(doc.select(".page, .stui-page").text())
            ?.groupValues?.get(2)?.toIntOrNull() ?: if (hasNext) page + 1 else page
        return PaginatedResult(unique, page, totalPages, hasNext || page < totalPages)
    }

    private fun parseVodDetail(doc: Document, vodId: Long): VodDetail {
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { resolveUrl(it) } ?: ""
        val body = doc.body().text()
        val director = extractMeta(body, "導演")
        val actors = extractMeta(body, "主演").split(Regex("[,，/、]")).map { it.trim() }.filter { it.isNotBlank() }
        val year = (extractMeta(body, "年代") + extractMeta(body, "年份")).filter { it.isDigit() }.take(4).toIntOrNull() ?: 0
        val category = extractMeta(body, "類型").ifBlank { extractMeta(body, "分類") }
        val status = extractMeta(body, "狀態")
        val synopsis = doc.select("p").firstOrNull { it.text().length > 50 && !it.text().contains("導演") }?.text()?.trim() ?: ""

        val groups = mutableListOf<EpisodeGroup>()
        for (container in doc.select("div.playlist-mobile.playlist")) {
            val gicoDiv = container.selectFirst("div.gico") ?: continue
            val name = gicoDiv.text().trim().replace(" ᴴᴰ", "").replace("ᴴᴰ", "").trim()
            val ul = container.selectFirst("ul[id^=con_playlist_]") ?: continue
            val eps = mutableListOf<Episode>(); var sId = 0
            for (link in ul.select("a[href~=/ep/]")) {
                val m = Regex("/ep/\\d+-(\\d+)-(\\d+)\\.html").find(link.attr("href")) ?: continue
                sId = m.groupValues[1].toIntOrNull() ?: continue
                val n = m.groupValues[2].toIntOrNull() ?: continue
                eps.add(Episode(n, link.text().trim(), link.attr("href")))
            }
            if (eps.isNotEmpty() && name.isNotBlank()) groups.add(EpisodeGroup(name, sId, eps.sortedBy { it.number }))
        }

        val sorted = groups.sortedWith(compareByDescending { g ->
            val i = stabilityOrder.indexOfFirst { g.sourceName.contains(it) }; if (i >= 0) stabilityOrder.size - i else -1
        })

        return VodDetail(Vod(vodId, sourceType, title, cover, category, year, status), director, actors, synopsis, sorted,
            seriesVods = parseSeriesVods(doc))
    }

    /**
     * Parse series vods from the 系列 section on detail pages.
     * Uses .box-title:has(h3:contains(系列)) to locate the container.
     */
    private fun parseSeriesVods(doc: Document): List<Vod> {
        val container = doc.selectFirst(".box-title:has(h3:contains(系列))")?.parent()
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

    private fun parsePlayerData(html: String): PlayerData {
        val idx = html.indexOf("player_data="); if (idx == -1) throw ScraperException("player_data not found")
        val s = html.indexOf('{', idx); if (s == -1) throw ScraperException("JSON not found")
        var d = 0; var e = -1
        for (i in s until minOf(s + 5000, html.length)) { when (html[i]) { '{' -> d++; '}' -> { d--; if (d == 0) { e = i; break } } } }
        if (e == -1) throw ScraperException("JSON incomplete")
        val json = JSONObject(html.substring(s, e + 1))
        var url = json.optString("url", "")
        url = when (json.optInt("encrypt", 0)) {
            1 -> String(android.util.Base64.decode(url, android.util.Base64.DEFAULT))
            2 -> String(android.util.Base64.decode(String(android.util.Base64.decode(url, android.util.Base64.DEFAULT)), android.util.Base64.DEFAULT))
            else -> url
        }
        if (url.isBlank()) throw ScraperException("Empty URL")
        return PlayerData(url, json.optInt("encrypt", 0), json.optString("from", ""))
    }

    private fun resolveUrl(url: String): String = when {
        url.isBlank() -> ""; url.startsWith("//") -> "https:$url"; url.startsWith("/") -> "$baseUrl$url"; else -> url
    }

    private fun extractMeta(text: String, key: String): String =
        Regex("$key[：:]\\s*(.+?)(?=\\s+\\S+[：:]|$)").find(text)?.groupValues?.get(1)?.trim() ?: ""
}
