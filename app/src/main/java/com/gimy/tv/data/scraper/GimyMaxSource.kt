package com.gimy.tv.data.scraper

import com.gimy.tv.data.endpoint.EndpointResolver
import com.gimy.tv.data.endpoint.gimyMirrorFor
import com.gimy.tv.data.scraper.parser.GimyParser
import com.gimy.tv.data.scraper.parser.GimyPaths
import com.gimy.tv.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import javax.inject.Inject

class GimyMaxSource @Inject constructor(
    private val client: OkHttpClient,
    private val endpointResolver: EndpointResolver,
) : SiteSource {

    override val sourceType = SourceType.GIMYMAX
    override val baseUrl: String get() = endpointResolver.getBaseUrl(sourceType)

    // gitube.tv runs the same template as gimytv.me, only the path tokens differ.
    // Paths and template follow whichever mirror EndpointResolver settled on, so a
    // fallback to a differently-shaped mirror parses correctly instead of silently
    // returning nothing. Cached per profile — rebuilding compiles regexes each time.
    private var mirrorCache: Pair<String?, Pair<GimyPaths, GimyParser>>? = null

    private fun mirror(): Pair<GimyPaths, GimyParser> {
        val profile = endpointResolver.getProfile(sourceType)
        mirrorCache?.takeIf { it.first == profile }?.let { return it.second }
        val m = gimyMirrorFor(profile)
        val built = m.paths to GimyParser(sourceType, m.paths, m.layout)
        mirrorCache = profile to built
        return built
    }

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
            val (paths, parser) = mirror()
            val url = if (page <= 1) "$baseUrl${paths.list}/$typeId.html"
            else "$baseUrl${paths.list}/$typeId-$page.html"
            parser.parseVodList(fetchDocument(url), baseUrl, page)
        }

    override suspend fun fetchVodDetail(vodId: Long): VodDetail =
        withContext(Dispatchers.IO) {
            val (paths, parser) = mirror()
            parser.parseVodDetail(fetchDocument("$baseUrl${paths.detail}/$vodId.html"), vodId, baseUrl)
        }

    override suspend fun fetchPlayerData(episodeUrl: String): PlayerData =
        withContext(Dispatchers.IO) {
            val url = if (episodeUrl.startsWith("http")) episodeUrl else "$baseUrl$episodeUrl"
            parseGimyPlayerData(fetchHtml(url))
        }

    /**
     * 搜尋走 MacCMS 的 ajax/suggest JSON 端點，關鍵字放在 POST body。
     *
     * 站方的 Cloudflare 規則是看 query string 有沒有 wd 參數（`/type/20.html` 通、
     * `/type/20.html?wd=X` 就回 Managed Challenge），所以只要不把關鍵字放進網址就完全
     * 不會被擋，也不再需要背景 WebView 解 cf_clearance——那在電視盒上並不可靠，解不出來
     * 時整組 Gimy 會從搜尋結果裡靜默消失。
     */
    override suspend fun search(keyword: String, page: Int): PaginatedResult<Vod> =
        withContext(Dispatchers.IO) {
            // 這個端點沒有分頁：page / pg 都被忽略，永遠回第一頁。第二頁之後直接回空，
            // 免得聚合搜尋的無限捲動一直拿到重複的第一頁。
            if (page > 1) return@withContext PaginatedResult(emptyList(), page, 1, false)
            val json = postForm("$baseUrl$GIMY_SUGGEST_PATH", gimySuggestForm(keyword))
            PaginatedResult(parseGimySuggest(json, sourceType), page, 1, hasMore = false)
        }

    override suspend fun probeListCount(baseUrl: String, profile: String?): Int = withContext(Dispatchers.IO) {
        runCatching {
            // Probe the candidate with ITS OWN profile, not the currently selected one.
            val m = gimyMirrorFor(profile)
            val probeParser = GimyParser(sourceType, m.paths, m.layout)
            val doc = Jsoup.parse(fetchHtml("$baseUrl${m.paths.list}/2.html"), baseUrl)
            probeParser.parseVodList(doc, baseUrl, 1).items.size
        }.getOrDefault(0)
    }

    private fun postForm(url: String, form: String): String {
        val body = form.toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val req = Request.Builder().url(url).post(body).build()
        return client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ScraperException("HTTP ${r.code}: $url")
            r.body?.string() ?: throw ScraperException("Empty: $url")
        }
    }

    private fun fetchHtml(url: String): String {
        val req = Request.Builder().url(url).build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ScraperException("HTTP ${resp.code}: $url")
            resp.body?.string() ?: throw ScraperException("Empty body: $url")
        }
    }

    private fun fetchDocument(url: String): Document = Jsoup.parse(fetchHtml(url), url)
}

class ScraperException(message: String) : Exception(message)
