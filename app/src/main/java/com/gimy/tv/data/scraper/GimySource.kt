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

/**
 * gimytv 與 gitube 這一系站台的共同實作。
 *
 * 兩邊跑的是同一套模板，差別只在網域與路徑 token（`/type` vs `/browse` 之類），
 * 而那些全都由 [EndpointResolver] 選出的 profile 決定——所以子類唯一要提供的
 * 就是 [sourceType]。
 *
 * 合併前這兩個檔案逐字相同、只差 sourceType 一行。那種重複的代價不在行數，
 * 在於「改一邊忘了改另一邊」：這裡每一個方法都跟站方模板綁在一起，
 * 站方一改版就要同步修，漏掉一邊的症狀是其中一個來源靜默地抓不到東西。
 */
abstract class GimySource(
    protected val client: OkHttpClient,
    protected val endpointResolver: EndpointResolver,
) : SiteSource {

    abstract override val sourceType: SourceType
    override val baseUrl: String get() = endpointResolver.getBaseUrl(sourceType)

    // Paths and template follow whichever mirror EndpointResolver settled on, so a
    // fallback to a differently-shaped mirror parses correctly instead of silently
    // returning nothing. Cached per profile — rebuilding compiles regexes each time.
    // @Volatile：@Singleton 之下多條 IO coroutine 會同時讀寫這個欄位。快取寫壞最多重建一次，
    // 但沒有它就是 data race，JMM 不保證另一條執行緒讀得到完整的物件。
    @Volatile
    private var mirrorCache: Pair<String?, Pair<GimyPaths, GimyParser>>? = null

    private fun mirror(): Pair<GimyPaths, GimyParser> {
        val profile = endpointResolver.getProfile(sourceType)
        mirrorCache?.takeIf { it.first == profile }?.let { return it.second }
        val m = gimyMirrorFor(profile)
        val built = m.paths to GimyParser(sourceType, m.paths, m.layout)
        mirrorCache = profile to built
        return built
    }

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

    override suspend fun fetchPlayerData(episodeUrl: String, deadlineMs: Long?): PlayerData =
        withContext(Dispatchers.IO) {
            val url = if (episodeUrl.startsWith("http")) episodeUrl else "$baseUrl$episodeUrl"
            parseGimyPlayerData(fetchHtml(url, deadlineMs))
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

    private fun fetchHtml(url: String, deadlineMs: Long? = null): String =
        client.fetchHtml(url, budgetMs = remainingBudget(deadlineMs))

    private fun fetchDocument(url: String): Document = client.fetchDocument(url)
}
