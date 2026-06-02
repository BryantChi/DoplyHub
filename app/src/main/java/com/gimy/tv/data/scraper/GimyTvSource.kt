package com.gimy.tv.data.scraper

import com.gimy.tv.data.endpoint.EndpointResolver
import com.gimy.tv.data.scraper.parser.GimyTvParser
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
            GimyTvParser.parseVodList(fetchDocument(url), baseUrl, page)
        }

    override suspend fun fetchVodDetail(vodId: Long): VodDetail =
        withContext(Dispatchers.IO) {
            GimyTvParser.parseVodDetail(fetchDocument("$baseUrl/vod/$vodId.html"), vodId, baseUrl)
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
            GimyTvParser.parseVodList(doc, baseUrl, page)
        }

    private fun fetchHtml(url: String): String {
        val req = Request.Builder().url(url).build()
        return client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ScraperException("HTTP ${r.code}: $url")
            r.body?.string() ?: throw ScraperException("Empty: $url")
        }
    }

    private fun fetchDocument(url: String): Document = Jsoup.parse(fetchHtml(url), url)

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
}
