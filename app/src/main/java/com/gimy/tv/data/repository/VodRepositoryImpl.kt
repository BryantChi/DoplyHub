package com.gimy.tv.data.repository

import com.gimy.tv.data.endpoint.EndpointResolver
import com.gimy.tv.data.local.dao.VodCacheDao
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
) : VodRepository {

    // In-memory home row cache (survives Activity recreation since VodRepositoryImpl is @Singleton)
    @Volatile private var gimyHomeCache: List<HomeRowData>? = null
    @Volatile private var gimyHomeCacheTime: Long = 0L
    @Volatile private var movieffmHomeCache: List<HomeRowData>? = null
    @Volatile private var movieffmHomeCacheTime: Long = 0L
    private val homeCacheTtlMs = 5 * 60 * 1000L // 5 minutes

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

    override suspend fun searchAllSources(keyword: String, page: Int): PaginatedResult<Vod> =
        coroutineScope {
            val gimyDeferred = async {
                try {
                    withTimeout(8000) { gimyTvSource.search(keyword, page) }
                } catch (_: Exception) {
                    PaginatedResult(emptyList(), page, 0, false)
                }
            }
            val ffmDeferred = async {
                try {
                    withTimeout(8000) { movieffmSource.search(keyword, page) }
                } catch (_: Exception) {
                    PaginatedResult(emptyList(), page, 0, false)
                }
            }

            val gimyResult = gimyDeferred.await()
            val ffmResult = ffmDeferred.await()

            val merged = mergeSearchResults(gimyResult.items, ffmResult.items)

            PaginatedResult(
                items = merged,
                currentPage = page,
                totalPages = maxOf(gimyResult.totalPages, ffmResult.totalPages),
                hasMore = gimyResult.hasMore || ffmResult.hasMore
            )
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

    /** Search BOTH sources for same-series items in parallel */
    override suspend fun searchSeriesVods(vod: Vod): List<Vod> {
        val baseTitle = extractSeriesBase(vod.title)
        if (baseTitle.isBlank() || baseTitle.length < 2) return emptyList()

        return coroutineScope {
            val primaryDeferred = async {
                try {
                    withTimeout(4000) {
                        getSource(vod.sourceType).search(baseTitle, 1).items
                    }
                } catch (_: Exception) { emptyList() }
            }
            val secondaryDeferred = async {
                try {
                    withTimeout(4000) {
                        val altSource = if (vod.sourceType == SourceType.MOVIEFFM) gimyTvSource else movieffmSource
                        altSource.search(baseTitle, 1).items
                    }
                } catch (_: Exception) { emptyList() }
            }
            val allResults = mergeSearchResults(primaryDeferred.await(), secondaryDeferred.await())
            allResults.filter { isSameSeries(baseTitle, it, vod.id) }
        }
    }

    private fun inferSeriesFromRelated(currentVod: Vod, relatedVods: List<Vod>): List<Vod> {
        val baseTitle = extractSeriesBase(currentVod.title)
        if (baseTitle.isBlank() || baseTitle.length < 2) return emptyList()
        return relatedVods.filter { isSameSeries(baseTitle, it, currentVod.id) }
    }

    // ── Enriched detail with cross-source episode groups ──

    override suspend fun getEnrichedVodDetail(sourceType: SourceType, vodId: Long, cachedPrimary: VodDetail?): VodDetail =
        coroutineScope {
            // Reuse cached primary if provided, otherwise fetch
            val primaryDetail = cachedPrimary ?: getSource(sourceType).fetchVodDetail(vodId)

            // Secondary source: best-effort, search by title
            val secondaryDeferred = async {
                try {
                    withTimeout(8000) {
                        val altSource = if (sourceType == SourceType.MOVIEFFM) gimyTvSource else movieffmSource
                        val searchResult = altSource.search(primaryDetail.vod.title, 1)

                        // Find best title match
                        val match = searchResult.items.firstOrNull {
                            normalizeTitle(it.title) == normalizeTitle(primaryDetail.vod.title)
                        } ?: return@withTimeout null

                        altSource.fetchVodDetail(match.id)
                    }
                } catch (_: Exception) {
                    null
                }
            }

            val secondaryDetail = secondaryDeferred.await()

            if (secondaryDetail != null) {
                // Merge episode groups: primary first, then secondary with source prefix
                val altSourceName = secondaryDetail.vod.sourceType.displayName
                val secondaryGroups = secondaryDetail.episodes.map { group ->
                    group.copy(
                        sourceName = "[$altSourceName] ${group.sourceName}",
                        sourceId = -(group.sourceId + 1000)
                    )
                }

                // Rank all groups by quality
                val allGroups = rankEpisodeGroups(primaryDetail.episodes + secondaryGroups)

                // Merge series vods from both sources (scraped only — search is done separately)
                val mergedSeries = mergeSearchResults(
                    primaryDetail.seriesVods, secondaryDetail.seriesVods
                )

                // Merge related vods from both sources (primary first, deduplicate)
                val mergedRelated = mergeSearchResults(
                    primaryDetail.relatedVods, secondaryDetail.relatedVods
                )

                // Remove series items from related to avoid duplication
                val seriesIds = mergedSeries.map { it.id }.toSet()
                val filteredRelated = mergedRelated.filter { it.id !in seriesIds }

                primaryDetail.copy(
                    episodes = allGroups,
                    seriesVods = mergedSeries,
                    relatedVods = filteredRelated
                )
            } else {
                primaryDetail
            }
        }

    // ── Quality-based episode group ranking ──

    /**
     * Rank episode groups by quality/stability.
     * Priority: gimymax top stable > movieffm direct m3u8 > gimymax secondary > unknown
     */
    private fun rankEpisodeGroups(groups: List<EpisodeGroup>): List<EpisodeGroup> {
        val gimyTopSources = listOf("順暢", "無盡", "極速", "高清")
        val gimySecondary = listOf("騰訊", "藍光", "4K", "優質", "非凡")

        return groups.sortedWith(compareBy { group ->
            val name = group.sourceName
            when {
                // Tier 1: gimymax top stable sources (0-3)
                gimyTopSources.any { name.contains(it) } ->
                    gimyTopSources.indexOfFirst { name.contains(it) }
                // Tier 2: movieffm direct sources (10-19)
                name.contains("MovieFFM") || (group.sourceId >= 1000 && group.sourceId > 0) ->
                    10 + (group.sourceId % 10)
                // Tier 3: gimymax secondary sources (20-28)
                gimySecondary.any { name.contains(it) } ->
                    20 + gimySecondary.indexOfFirst { name.contains(it) }
                // Tier 4: unknown (99)
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
