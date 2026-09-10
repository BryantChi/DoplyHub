package com.gimy.tv.data.scraper

import com.gimy.tv.data.endpoint.EndpointResolver
import com.gimy.tv.data.scraper.parser.GimyParser
import com.gimy.tv.data.scraper.parser.GimyPaths
import com.gimy.tv.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import javax.inject.Inject

class GimyTvSource @Inject constructor(
    private val client: OkHttpClient,
    private val endpointResolver: EndpointResolver,
) : SiteSource {

    override val sourceType = SourceType.GIMYTV
    override val baseUrl: String get() = endpointResolver.getBaseUrl(sourceType)

    private val paths = GimyPaths(list = "/type", detail = "/vod", episode = "/ep")
    private val parser = GimyParser(sourceType, paths)

    // Only the search path is challenged; list/detail/play are served normally.
    override val cloudflareWarmUpUrl: String
        get() = "$baseUrl/search/%E7%86%B1%E9%96%80----------1---.html"

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
            val url = if (page <= 1) "$baseUrl${paths.list}/$typeId.html"
            else "$baseUrl${paths.list}/$typeId-$page.html"
            parser.parseVodList(fetchDocument(url), baseUrl, page)
        }

    override suspend fun fetchVodDetail(vodId: Long): VodDetail =
        withContext(Dispatchers.IO) {
            parser.parseVodDetail(fetchDocument("$baseUrl${paths.detail}/$vodId.html"), vodId, baseUrl)
        }

    override suspend fun fetchPlayerData(episodeUrl: String): PlayerData =
        withContext(Dispatchers.IO) {
            val url = if (episodeUrl.startsWith("http")) episodeUrl else "$baseUrl$episodeUrl"
            parseGimyPlayerData(fetchHtml(url))
        }

    override suspend fun search(keyword: String, page: Int): PaginatedResult<Vod> =
        withContext(Dispatchers.IO) {
            val enc = java.net.URLEncoder.encode(keyword, "UTF-8")
            val doc = fetchDocument("$baseUrl/search/$enc----------$page---.html")
            doc.select("#stickyside").remove()
            // Search pages use the search-item template, not the card template used by lists.
            parser.parseSearchResults(doc, baseUrl, page)
        }

    override suspend fun probeListCount(baseUrl: String): Int = withContext(Dispatchers.IO) {
        runCatching {
            val doc = Jsoup.parse(fetchHtml("$baseUrl${paths.list}/2.html"), baseUrl)
            parser.parseVodList(doc, baseUrl, 1).items.size
        }.getOrDefault(0)
    }

    private fun fetchHtml(url: String): String {
        val req = Request.Builder().url(url).build()
        return client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ScraperException("HTTP ${r.code}: $url")
            r.body?.string() ?: throw ScraperException("Empty: $url")
        }
    }

    private fun fetchDocument(url: String): Document = Jsoup.parse(fetchHtml(url), url)
}
