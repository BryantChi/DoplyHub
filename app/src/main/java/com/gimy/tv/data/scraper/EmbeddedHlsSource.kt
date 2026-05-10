package com.gimy.tv.data.scraper

import com.gimy.tv.data.endpoint.EndpointResolver
import com.gimy.tv.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Base class for sites where the detail page directly embeds an HLS m3u8 URL via a
 * single JavaScript variable or function call (jable.tv → `var hlsUrl = '...m3u8'`,
 * xnxx.com → `html5player.setVideoHLS('...m3u8')`). Token is IP-bound so the App
 * playing on the same NAT/public IP as our scrape works without further auth.
 *
 * Subclasses provide:
 *   - sourceType / baseUrl
 *   - listPath builders for the rows we want (hot / latest / tag / best / etc.)
 *   - detail card selector (how we extract Vod cards from a list HTML)
 *   - hlsRegex (matches the inline JS that holds the m3u8 URL)
 *
 * Categories/typeId are intentionally absent — these sites are organized by path
 * (e.g. /hot/, /best/this_week, /tags/{slug}/) rather than numeric typeIds, so they
 * don't fit the MacCMS categoryMap model. AdultPlusScreen wires list URLs directly.
 */
abstract class EmbeddedHlsSource(
    protected val client: OkHttpClient,
    protected val endpointResolver: EndpointResolver,
) : SiteSource {

    abstract override val sourceType: SourceType
    override val baseUrl: String get() = endpointResolver.getBaseUrl(sourceType)

    /** Regex that captures the m3u8 URL from the detail page's inline JS. Group 1 = the URL. */
    protected abstract val hlsRegex: Regex

    /** Build a list URL for a given path key. AdultPlusScreen passes opaque keys like
     *  "hot", "latest", "best/this_week", "tags/japanese". */
    protected abstract fun buildListUrlForPath(path: String, page: Int): String

    /** Parse a list-page document into Vod cards. Default implementation matches a wide
     *  set of common selectors; subclasses override if their HTML differs. */
    protected abstract fun parseListCards(doc: Document): List<Vod>

    /** Extract (title, cover, year) metadata from a detail page. */
    protected abstract fun parseDetailMeta(doc: Document, vodId: Long): Triple<String, String, Int>

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // ─── Slug ↔ stableId cache ───
    // jable / xnxx use slug-based detail URLs (e.g. "fns-203", "1h3vtvb4/the_widow…")
    // but Vod.id is Long. We hash the slug into a stable Long and remember the reverse
    // mapping so detailUrlFor() can reconstruct the URL. Cache lives for the process —
    // first list visit re-populates it after cold start (history/favorite are best-effort).
    //
    // Bounded LRU (2000 entries each) via shared TtlLruCache — previously this was a
    // hand-rolled `Collections.synchronizedMap(LinkedHashMap with removeEldestEntry)`
    // duplicated across scrapers. Eviction means a stale slug recomputes the same id
    // (deterministic hash), so no correctness issue — only "soft cold start" for the
    // evicted slug if user opens it from history.

    private val slugCacheCapacity = 2000
    protected val slugToId = com.gimy.tv.data.cache.TtlLruCache<String, Long>(slugCacheCapacity)
    protected val idToSlug = com.gimy.tv.data.cache.TtlLruCache<Long, String>(slugCacheCapacity)

    protected fun stableId(slug: String): Long = slugToId.getOrPut(slug) {
        val id = stableHashLong(slug)
        idToSlug.put(id, slug)
        id
    }

    protected fun slugForId(vodId: Long): String? = idToSlug.get(vodId)

    private fun stableHashLong(s: String): Long {
        var h = 1125899906842597L
        for (c in s) h = 31L * h + c.code.toLong()
        return h and 0x7FFFFFFFFFFFFFFFL  // strip sign so Vod.id is non-negative
    }

    // ─── SiteSource impl ───
    // These sites have no traditional categories; AdultPlusScreen drives list URLs directly
    // via fetchVodListByPath() below. The legacy fetchVodList(typeId, page) is unused.

    override suspend fun fetchCategories(): List<Category> = emptyList()

    override suspend fun fetchVodList(typeId: Int, page: Int): PaginatedResult<Vod> =
        PaginatedResult(emptyList(), page, 0, false)

    /**
     * Subclasses may override to compute the maximum page number from a list-page
     * Document (e.g. Jable's Bootstrap pagination uses `.page-link[data-parameters="…from:N"]`).
     * Returning a positive value short-circuits the generic next-link selector below.
     */
    protected open fun parseMaxPage(doc: Document): Int? = null

    /** Path-based list fetch — AdultPlusScreen calls this directly with row-specific paths. */
    suspend fun fetchVodListByPath(path: String, page: Int): PaginatedResult<Vod> =
        withContext(Dispatchers.IO) {
            val doc = fetchDocument(buildListUrlForPath(path, page))
            val items = parseListCards(doc)
            val maxPage = parseMaxPage(doc)
            val hasNext = if (maxPage != null) maxPage > page
                else doc.select("a:contains(下一頁), a:contains(Next), a.next, a[rel=next]").isNotEmpty()
            PaginatedResult(items, page, if (hasNext) page + 1 else page, hasNext)
        }

    override suspend fun fetchVodDetail(vodId: Long): VodDetail =
        withContext(Dispatchers.IO) {
            val doc = fetchDocument(detailUrlFor(vodId))
            val (title, cover, year) = parseDetailMeta(doc, vodId)
            // Embed sites don't have multi-source episode lists — single virtual episode
            // points back at the same detail URL; PlayerData resolution happens in fetchPlayerData.
            // Use the site's display name as the line label so the UI shows e.g. "Jable" or
            // "XNXX" instead of the generic "HLS" tech term that confused users.
            val ep = Episode(1, title, "embed:$vodId")
            val group = EpisodeGroup(sourceType.displayName, 1, listOf(ep))
            VodDetail(
                Vod(vodId, sourceType, title, cover, "", year, ""),
                "", emptyList(), "", listOf(group),
            )
        }

    /** Subclasses construct the canonical detail URL from a vodId. */
    protected abstract fun detailUrlFor(vodId: Long): String

    override suspend fun fetchPlayerData(episodeUrl: String): PlayerData =
        withContext(Dispatchers.IO) {
            // Strip our "embed:{vodId}" sentinel and refetch the detail page to extract m3u8
            val vodId = episodeUrl.removePrefix("embed:").toLongOrNull()
                ?: throw ScraperException("Invalid embed url: $episodeUrl")
            val html = fetchHtml(detailUrlFor(vodId))
            val match = hlsRegex.find(html)
                ?: throw ScraperException("hlsUrl not found on detail page")
            val url = match.groupValues[1]
            if (url.isBlank()) throw ScraperException("Empty hls URL")
            PlayerData(url, encrypt = 0, from = sourceType.name.lowercase())
        }

    override suspend fun search(keyword: String, page: Int): PaginatedResult<Vod> =
        withContext(Dispatchers.IO) {
            // Default: most embed sites support /search/?q={kw} or /?k={kw}; subclasses override
            PaginatedResult(emptyList(), page, 0, false)
        }

    // ─── HTTP ───

    protected fun fetchHtml(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", baseUrl)
            .build()
        return client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ScraperException("HTTP ${r.code}: $url")
            r.body?.string() ?: throw ScraperException("Empty body: $url")
        }
    }

    protected fun fetchDocument(url: String): Document = Jsoup.parse(fetchHtml(url), url)
}
