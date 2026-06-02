package com.gimy.tv.data.scraper

import com.gimy.tv.data.endpoint.EndpointResolver
import com.gimy.tv.data.scraper.parser.GimyMaxParser
import com.gimy.tv.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import javax.inject.Inject

class GimyMaxSource @Inject constructor(
    private val client: OkHttpClient,
    private val endpointResolver: EndpointResolver,
) : SiteSource {

    override val sourceType = SourceType.GIMYMAX
    override val baseUrl: String get() = endpointResolver.getBaseUrl(sourceType)

    override suspend fun fetchCategories(): List<Category> = listOf(
        Category(2, "電視劇", sourceType), Category(1, "電影", sourceType),
        Category(4, "動漫", sourceType), Category(29, "綜藝", sourceType),
        Category(13, "陸劇", sourceType), Category(20, "韓劇", sourceType),
        Category(16, "美劇", sourceType), Category(15, "日劇", sourceType),
        Category(14, "台劇", sourceType), Category(21, "港劇", sourceType),
        Category(22, "紀錄片", sourceType),
    )

    override suspend fun fetchVodList(typeId: Int, page: Int): PaginatedResult<Vod> =
        withContext(Dispatchers.IO) {
            val url = if (page <= 1) "$baseUrl/type/$typeId.html" else "$baseUrl/type/$typeId-$page.html"
            GimyMaxParser.parseVodList(fetchDocument(url), baseUrl, page)
        }

    override suspend fun fetchVodDetail(vodId: Long): VodDetail =
        withContext(Dispatchers.IO) {
            GimyMaxParser.parseVodDetail(fetchDocument("$baseUrl/vod/$vodId.html"), vodId, baseUrl)
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
            doc.select("#stickyside").remove()
            GimyMaxParser.parseVodList(doc, baseUrl, page)
        }

    private fun fetchHtml(url: String): String {
        val req = Request.Builder().url(url).build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ScraperException("HTTP ${resp.code}: $url")
            resp.body?.string() ?: throw ScraperException("Empty body: $url")
        }
    }

    private fun fetchDocument(url: String): Document = Jsoup.parse(fetchHtml(url), url)

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
}

class ScraperException(message: String) : Exception(message)
