package com.gimy.tv.data.repository

import com.gimy.tv.data.endpoint.EndpointResolver
import com.gimy.tv.data.local.dao.VodCacheDao
import com.gimy.tv.data.preferences.SourcePreferencesRepository
import com.gimy.tv.data.scraper.EynyTvSource
import com.gimy.tv.data.scraper.GimyMaxSource
import com.gimy.tv.data.scraper.GimyTvSource
import com.gimy.tv.data.scraper.GimyTwSource
import com.gimy.tv.data.scraper.ImapleTvSource
import com.gimy.tv.data.scraper.Kubo123Source
import com.gimy.tv.data.scraper.MomovodSource
import com.gimy.tv.data.scraper.MovieffmSource
import com.gimy.tv.data.scraper.SiteSource
import com.gimy.tv.domain.model.*
import com.gimy.tv.domain.repository.HomeRowData
import com.gimy.tv.domain.repository.VodRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VodRepositoryImpl @Inject constructor(
    private val gimyMaxSource: GimyMaxSource,
    private val gimyTvSource: GimyTvSource,
    private val movieffmSource: MovieffmSource,
    private val gimyTwSource: GimyTwSource,
    private val eynyTvSource: EynyTvSource,
    private val imapleTvSource: ImapleTvSource,
    private val momovodSource: MomovodSource,
    private val kubo123Source: Kubo123Source,
    private val vodCacheDao: VodCacheDao,
    private val okHttpClient: OkHttpClient,
    private val endpointResolver: EndpointResolver,
    private val sourcePreferencesRepository: SourcePreferencesRepository,
) : VodRepository {

    // In-memory home row cache (survives Activity recreation since VodRepositoryImpl is @Singleton)
    @Volatile private var gimyHomeCache: List<HomeRowData>? = null
    @Volatile private var gimyHomeCacheTime: Long = 0L
    @Volatile private var movieffmHomeCache: List<HomeRowData>? = null
    @Volatile private var movieffmHomeCacheTime: Long = 0L
    private val homeCacheTtlMs = 5 * 60 * 1000L // 5 minutes

    // Search/detail caches: 60s TTL; LRU-evicted by capacity. Memory-only — they exist to
    // dedupe rapid repeat queries (e.g. switching source chips on the search page) so we
    // don't fan out 8 sources for a question we just answered.
    private val searchCacheTtlMs = 60 * 1000L
    private val searchCacheCapacity = 10
    private val detailCacheTtlMs = 60 * 1000L
    private val detailCacheCapacity = 20

    private data class SearchCacheEntry(val result: PaginatedResult<Vod>, val timestamp: Long)
    private data class DetailCacheEntry(val detail: VodDetail, val timestamp: Long)

    private val searchCache: LinkedHashMap<String, SearchCacheEntry> =
        LinkedHashMap(searchCacheCapacity, 0.75f, /* accessOrder = */ true)
    private val detailCache: LinkedHashMap<String, DetailCacheEntry> =
        LinkedHashMap(detailCacheCapacity, 0.75f, true)

    private fun searchCacheGet(key: String): PaginatedResult<Vod>? = synchronized(searchCache) {
        val entry = searchCache[key] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > searchCacheTtlMs) {
            searchCache.remove(key); return null
        }
        entry.result
    }

    private fun searchCachePut(key: String, result: PaginatedResult<Vod>) = synchronized(searchCache) {
        searchCache[key] = SearchCacheEntry(result, System.currentTimeMillis())
        while (searchCache.size > searchCacheCapacity) {
            val it = searchCache.entries.iterator(); it.next(); it.remove()
        }
    }

    private fun detailCacheGet(key: String): VodDetail? = synchronized(detailCache) {
        val entry = detailCache[key] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > detailCacheTtlMs) {
            detailCache.remove(key); return null
        }
        entry.detail
    }

    private fun detailCachePut(key: String, detail: VodDetail) = synchronized(detailCache) {
        detailCache[key] = DetailCacheEntry(detail, System.currentTimeMillis())
        while (detailCache.size > detailCacheCapacity) {
            val it = detailCache.entries.iterator(); it.next(); it.remove()
        }
    }

    private fun getSource(sourceType: SourceType): SiteSource = when (sourceType) {
        SourceType.GIMYMAX -> gimyMaxSource
        SourceType.GIMYTV -> gimyTvSource
        SourceType.MOVIEFFM -> movieffmSource
        SourceType.GIMY_TW -> gimyTwSource
        SourceType.EYNY_TV -> eynyTvSource
        SourceType.IMAPLE_TV -> imapleTvSource
        SourceType.MOMOVOD -> momovodSource
        SourceType.KUBO123 -> kubo123Source
    }

    // ── Basic operations ──
    // vodId and typeId are source-specific — fallback to another source with the same
    // ID is meaningless and causes "Unknown movieffm ID" errors. Only keyword-based
    // operations (search) can safely fall back across sources.

    override suspend fun getCategories(sourceType: SourceType): List<Category> {
        return getSource(sourceType).fetchCategories()
    }

    override suspend fun getVodList(
        sourceType: SourceType, typeId: Int, page: Int
    ): PaginatedResult<Vod> {
        return getSource(sourceType).fetchVodList(typeId, page)
    }

    override suspend fun getVodDetail(sourceType: SourceType, vodId: Long): VodDetail {
        return getSource(sourceType).fetchVodDetail(vodId)
    }

    override suspend fun getPlayerData(sourceType: SourceType, episodeUrl: String): PlayerData {
        // Direct m3u8 URLs from movieffm — handle without contacting any source
        if (episodeUrl.startsWith("http") && (episodeUrl.contains(".m3u8") || episodeUrl.contains("/video/"))) {
            return PlayerData(streamUrl = episodeUrl, encrypt = 0, from = "movieffm")
        }
        return getSource(sourceType).fetchPlayerData(episodeUrl)
    }

    override suspend fun search(
        sourceType: SourceType, keyword: String, page: Int
    ): PaginatedResult<Vod> {
        // Keywords are source-independent — safe to fall back
        return try {
            getSource(sourceType).search(keyword, page)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (sourceType != SourceType.MOVIEFFM) movieffmSource.search(keyword, page) else throw e
        }
    }

    // ── Cross-source search ──

    /** Search across enabled sources in parallel. Order = display priority (stability/popularity).
     *  User-disabled sources are filtered out via SourcePreferencesRepository.enabledSources.
     *  Each source has its own 5s timeout — slow/failing sources don't block fast ones. */
    private val searchOrder: List<SiteSource> get() {
        val enabled = sourcePreferencesRepository.enabledSources.value
        return listOf(
            gimyTvSource, gimyMaxSource, imapleTvSource, gimyTwSource,
            eynyTvSource, momovodSource, kubo123Source, movieffmSource,
        ).filter { it.sourceType in enabled }
    }

    /** Best-effort adult-content filter for non-18+ paths.
     *  Phase 4 will upgrade this to a precise per-source typeId match (Vod model needs typeId field).
     *  Until then, fall back to category/title string heuristics — covers the common cases
     *  (倫理片 / 情色 / 成人) without needing the scrapers to expose typeId. */
    private fun looksAdult(vod: Vod): Boolean {
        val cat = vod.category
        val title = vod.title
        return cat.contains("倫理") || cat.contains("情色") || cat.contains("成人") ||
            title.contains("成人") || title.contains("18+")
    }

    override suspend fun searchAllSources(keyword: String, page: Int): PaginatedResult<Vod> {
        val cacheKey = "$keyword|$page"
        searchCacheGet(cacheKey)?.let { return it }

        val result = coroutineScope {
            val deferreds = searchOrder.map { src ->
                async {
                    try {
                        withTimeout(5_000) { src.search(keyword, page) }
                    } catch (_: Exception) {
                        PaginatedResult(emptyList<Vod>(), page, 0, false)
                    }
                }
            }
            val results = deferreds.map { it.await() }

            // Merge in priority order, dedupe across sources, drop adult content
            var merged = emptyList<Vod>()
            for (r in results) merged = mergeSearchResults(merged, r.items.filterNot { looksAdult(it) })

            PaginatedResult(
                items = merged,
                currentPage = page,
                totalPages = results.maxOfOrNull { it.totalPages } ?: 0,
                hasMore = results.any { it.hasMore },
            )
        }
        searchCachePut(cacheKey, result)
        return result
    }

    private fun mergeSearchResults(primary: List<Vod>, secondary: List<Vod>): List<Vod> {
        val result = primary.toMutableList()
        val primaryTitles = primary.map { normalizeTitle(it.title) }.toSet()

        for (vod in secondary) {
            if (normalizeTitle(vod.title) !in primaryTitles) {
                result.add(vod)
            }
        }
        return result
    }

    private fun normalizeTitle(title: String): String {
        return title
            .replace(Regex("[\\s　]+"), "")
            .replace(Regex("[（）()\\[\\]【】《》]"), "")
            .lowercase()
            .replace(Regex("第[一二三四五六七八九十\\d]+季"), "")
            .replace(Regex("season\\s*\\d+", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    /** Extract the core series name by stripping subtitles, episode arcs, etc. */
    private fun extractSeriesBase(title: String): String {
        return normalizeTitle(title)
            .replace(Regex("最終季.*"), "")
            .replace(Regex("完結篇.*"), "")
            .replace(Regex("特別篇.*"), "")
            .replace(Regex("劇場版.*"), "")
            .replace(Regex("外傳.*"), "")
            .replace(Regex("ova.*"), "")
            .replace(Regex("篇$"), "")
            .replace(Regex("[.:：・\\-~～]+.*"), "") // strip after punctuation (subtitle separator)
            .trim()
    }

    private fun isSameSeries(baseTitle: String, candidate: Vod, currentId: Long): Boolean {
        if (candidate.id == currentId) return false
        val candidateBase = extractSeriesBase(candidate.title)
        if (candidateBase.isBlank()) return false
        return baseTitle == candidateBase ||
            (baseTitle.length >= 3 && candidateBase.contains(baseTitle)) ||
            (candidateBase.length >= 3 && baseTitle.contains(candidateBase))
    }

    /** Search ALL 8 sources in parallel for same-series items. Each source has its own 4s timeout. */
    override suspend fun searchSeriesVods(vod: Vod): List<Vod> {
        val baseTitle = extractSeriesBase(vod.title)
        if (baseTitle.isBlank() || baseTitle.length < 2) return emptyList()

        return coroutineScope {
            val deferreds = searchOrder.map { src ->
                async {
                    try {
                        withTimeout(4_000) { src.search(baseTitle, 1).items }
                    } catch (_: Exception) { emptyList<Vod>() }
                }
            }
            // Merge results in priority order, drop adult content
            var all = emptyList<Vod>()
            for (d in deferreds) all = mergeSearchResults(all, d.await().filterNot { looksAdult(it) })
            all.filter { isSameSeries(baseTitle, it, vod.id) }
        }
    }

    private fun inferSeriesFromRelated(currentVod: Vod, relatedVods: List<Vod>): List<Vod> {
        val baseTitle = extractSeriesBase(currentVod.title)
        if (baseTitle.isBlank() || baseTitle.length < 2) return emptyList()
        return relatedVods.filter { isSameSeries(baseTitle, it, currentVod.id) }
    }

    // ── Enriched detail with cross-source episode groups ──

    override suspend fun getEnrichedVodDetail(sourceType: SourceType, vodId: Long, cachedPrimary: VodDetail?): VodDetail {
        val cacheKey = "${sourceType.name}|$vodId"
        // Cache hit returns the fully-enriched (8-source merged) detail — skips re-querying 7 sources.
        // Caller's `cachedPrimary` is only the primary-source detail, so prefer our richer cache.
        detailCacheGet(cacheKey)?.let { return it }

        return coroutineScope {
            // Reuse caller-provided primary if present, otherwise fetch
            val primaryDetail = cachedPrimary ?: getSource(sourceType).fetchVodDetail(vodId)
            val primaryTitle = primaryDetail.vod.title
            val primaryYear = primaryDetail.vod.year
            val normalizedPrimary = normalizeTitle(primaryTitle)

            // Query the other 7 sources in parallel for same title (+ year if available).
            // Each source has its own 5s timeout — slow/failing sources don't block the rest.
            val otherSources = searchOrder.filter { it.sourceType != sourceType }
            val matchedDetails = otherSources.map { src ->
                async {
                    try {
                        withTimeout(5_000) {
                            val results = src.search(primaryTitle, 1).items
                            val match = results.firstOrNull {
                                !looksAdult(it) &&
                                    normalizeTitle(it.title) == normalizedPrimary &&
                                    (primaryYear == 0 || it.year == 0 || it.year == primaryYear)
                            } ?: return@withTimeout null
                            src.fetchVodDetail(match.id)
                        }
                    } catch (_: Exception) { null }
                }
            }.mapNotNull { it.await() }

            if (matchedDetails.isEmpty()) {
                detailCachePut(cacheKey, primaryDetail)
                return@coroutineScope primaryDetail
            }

            // Merge episode groups: primary first, then each matched secondary with source prefix
            val secondaryGroups = matchedDetails.flatMap { detail ->
                val displayName = detail.vod.sourceType.displayName
                detail.episodes.map { group ->
                    group.copy(
                        sourceName = "[$displayName] ${group.sourceName}",
                        sourceId = -(detail.vod.sourceType.ordinal * 100 + group.sourceId + 1)
                    )
                }
            }
            val allGroups = rankEpisodeGroups(primaryDetail.episodes + secondaryGroups)

            // Merge series + related from all sources (priority order, dedupe by title, drop adult)
            var mergedSeries = primaryDetail.seriesVods.filterNot { looksAdult(it) }
            var mergedRelated = primaryDetail.relatedVods.filterNot { looksAdult(it) }
            for (d in matchedDetails) {
                mergedSeries = mergeSearchResults(mergedSeries, d.seriesVods.filterNot { looksAdult(it) })
                mergedRelated = mergeSearchResults(mergedRelated, d.relatedVods.filterNot { looksAdult(it) })
            }
            // Remove series items from related to avoid duplication
            val seriesIds = mergedSeries.map { it.id }.toSet()
            val filteredRelated = mergedRelated.filter { it.id !in seriesIds }

            primaryDetail.copy(
                episodes = allGroups,
                seriesVods = mergedSeries,
                relatedVods = filteredRelated,
            ).also { detailCachePut(cacheKey, it) }
        }
    }

    // ── Quality-based episode group ranking ──

    /**
     * Rank episode groups by quality/stability across all 8 sources.
     *
     * Tiers (lower = preferred):
     *   1A. GimyMax/GimyTv top stable (順暢/無盡/極速/高清, no 雲 suffix) — historically most reliable
     *   1B. New MacCMS site lines (卧龍雲/索尼雲/...) — m3u8 direct, no encryption
     *   2.  Movieffm direct sources
     *   3.  GimyMax/GimyTv secondary (騰訊/藍光/4K/優質/非凡)
     *   4.  Unknown
     *
     * The `!name.contains("雲")` guard prevents 「無盡雲」from matching gimyTop's「無盡」tag.
     */
    private fun rankEpisodeGroups(groups: List<EpisodeGroup>): List<EpisodeGroup> {
        val gimyTopSources = listOf("順暢", "無盡", "極速", "高清")
        val newSiteTopSources = listOf("卧龍雲", "索尼雲", "無盡雲", "閃電雲", "極速雲", "優質雲")
        val gimySecondary = listOf("騰訊", "藍光", "4K", "優質", "非凡")

        return groups.sortedWith(compareBy { group ->
            val name = group.sourceName
            when {
                // Tier 1A: GimyMax/GimyTv top stable (0-3)
                !name.contains("雲") && gimyTopSources.any { name.contains(it) } ->
                    gimyTopSources.indexOfFirst { name.contains(it) }
                // Tier 1B: new MacCMS site lines (4-9)
                newSiteTopSources.any { name.contains(it) } ->
                    4 + newSiteTopSources.indexOfFirst { name.contains(it) }
                // Tier 2: Movieffm direct (15-24)
                name.contains("MovieFFM") || (group.sourceId >= 1000 && group.sourceId > 0) ->
                    15 + (group.sourceId % 10)
                // Tier 3: GimyMax/GimyTv secondary (25-29)
                !name.contains("雲") && gimySecondary.any { name.contains(it) } ->
                    25 + gimySecondary.indexOfFirst { name.contains(it) }
                // Tier 4: unknown
                else -> 99
            }
        })
    }

    // ── Home page mixed content ──

    // Movieffm typeId → matching gimymax typeId for interleaving
    private val ffmToGimyMap = mapOf(
        101 to 1, 201 to 20, 202 to 13, 203 to 16, 204 to 21,
        205 to 4, 207 to 14, 208 to 15, 206 to 29
    )

    override suspend fun getGimyHomeRows(forceRefresh: Boolean): List<HomeRowData> {
        // Return memory cache if fresh, unless forceRefresh bypasses it
        if (!forceRefresh) {
            gimyHomeCache?.let { cached ->
                if (System.currentTimeMillis() - gimyHomeCacheTime < homeCacheTtlMs) return cached
            }
        } else {
            // Wipe OkHttp disk cache so refresh truly hits the network instead of 304-cached body
            try {
                okHttpClient.cache?.evictAll()
            } catch (_: Exception) { }
            // Also re-resolve mirror endpoints in background — picks up new domains on next request
            endpointResolver.forceRefreshAsync()
        }
        val rows = coroutineScope {
            val categories = listOf(
                20 to "韓劇", 13 to "陸劇", 16 to "美劇", 21 to "日劇",
                1 to "電影", 4 to "動漫", 14 to "台劇", 15 to "港劇",
                29 to "綜藝", 3 to "紀錄片"
            )
            categories.map { (typeId, name) ->
                async {
                    try {
                        withTimeout(8000) {
                            val result = gimyTvSource.fetchVodList(typeId, 1)
                            HomeRowData(name, SourceType.GIMYTV, typeId, result.items.take(15))
                        }
                    } catch (_: Exception) { null }
                }
            }.mapNotNull { it.await() }.filter { it.items.isNotEmpty() }
        }
        // Only cache non-empty results
        if (rows.isNotEmpty()) {
            gimyHomeCache = rows
            gimyHomeCacheTime = System.currentTimeMillis()
        }
        return rows
    }

    override suspend fun getMovieffmHomeRows(forceRefresh: Boolean): List<HomeRowData> {
        // Return memory cache if fresh, unless forceRefresh bypasses it
        if (!forceRefresh) {
            movieffmHomeCache?.let { cached ->
                if (System.currentTimeMillis() - movieffmHomeCacheTime < homeCacheTtlMs) return cached
            }
        } else {
            endpointResolver.forceRefreshAsync()
        }
        val rows = coroutineScope {
            val categories = listOf(
                101 to "熱門電影", 201 to "韓劇", 202 to "陸劇",
                203 to "美劇", 204 to "日劇", 205 to "動漫",
                207 to "台劇", 208 to "港劇", 206 to "綜藝"
            )
            // All parallel — OkHttp's per-host limit handles throttling
            categories.map { (typeId, name) ->
                async {
                    try {
                        withTimeout(6000) {
                            val result = movieffmSource.fetchVodList(typeId, 1)
                            HomeRowData(name, SourceType.MOVIEFFM, typeId, result.items.take(15))
                        }
                    } catch (_: Exception) { null }
                }
            }.mapNotNull { it.await() }.filter { it.items.isNotEmpty() }
        }
        if (rows.isNotEmpty()) {
            movieffmHomeCache = rows
            movieffmHomeCacheTime = System.currentTimeMillis()
        }
        return rows
    }
}
