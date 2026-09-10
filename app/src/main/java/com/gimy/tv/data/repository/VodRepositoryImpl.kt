package com.gimy.tv.data.repository

import com.gimy.tv.data.cache.TtlLruCache
import com.gimy.tv.data.endpoint.EndpointResolver
import com.gimy.tv.data.local.dao.VodCacheDao
import com.gimy.tv.data.preferences.SourcePreferencesRepository
import com.gimy.tv.data.scraper.EynyTvSource
import com.gimy.tv.data.scraper.Forum5278Source
import com.gimy.tv.data.scraper.GimyMaxSource
import com.gimy.tv.data.scraper.GimyTvSource
import com.gimy.tv.data.scraper.GimyTwSource
import com.gimy.tv.data.scraper.ImapleTvSource
import com.gimy.tv.data.scraper.JableTvSource
import com.gimy.tv.data.scraper.Kubo123Source
import com.gimy.tv.data.scraper.MomovodSource
import com.gimy.tv.data.scraper.MovieffmSource
import com.gimy.tv.data.scraper.SiteSource
import com.gimy.tv.data.scraper.XnxxSource
import com.gimy.tv.domain.model.*
import com.gimy.tv.domain.model.Confidence
import com.gimy.tv.domain.model.EpisodeStatus
import com.gimy.tv.domain.repository.HomeRowData
import com.gimy.tv.domain.repository.TitleLookup
import com.gimy.tv.domain.util.parseEpisodeStatus
import com.gimy.tv.domain.repository.VodRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Compute cross-site aggregated status from POST-normalize episodes and the
 * already-fallback-applied per-site metadata. Extracted so the rule can be
 * unit-tested independently of the full repository wiring.
 *
 * Inputs:
 *   - normalizedMaxEp: max(episode.number) across all surviving lines after
 *     EpisodeNormalizer ran. May be 0 when no lines parsed any episodes.
 *   - siteMetadata: per-site Vods (primary entry MUST already carry
 *     fallback-resolved status, see getEnrichedVodDetail).
 *   - primaryFallbackStatus: parseEpisodeStatus(statusFallback) for the
 *     "no other branch matched" fallthrough.
 *
 * Output principle: badge number can NEVER exceed normalizedMaxEp when
 * normalizedMaxEp > 0 — the user can't play episodes we don't have. When all
 * lines failed to parse (rare, scraper bug), trust the highest declared count.
 *
 * @VisibleForTesting — internal visibility so unit tests in the same package
 * can call it directly without requiring full repository wiring.
 */
internal fun computeAggregatedStatus(
    normalizedMaxEp: Int,
    siteMetadata: Map<SourceType, Vod>,
    primaryFallbackStatus: EpisodeStatus,
): EpisodeStatus {
    val anyFinished = siteMetadata.values.any { it.siteStatus is EpisodeStatus.Finished }
    val highestSiteDeclared = siteMetadata.values.maxOfOrNull {
        when (val st = it.siteStatus) {
            is EpisodeStatus.InProgress -> st.latest
            is EpisodeStatus.Finished -> st.total
            else -> 0
        }
    } ?: 0
    val winnerCount = if (normalizedMaxEp > 0) normalizedMaxEp else highestSiteDeclared

    return when {
        anyFinished -> EpisodeStatus.Finished(total = winnerCount)
        winnerCount > 1 -> EpisodeStatus.InProgress(
            latest = winnerCount,
            confidence = Confidence.CrossSiteMax,
        )
        primaryFallbackStatus !is EpisodeStatus.Empty -> primaryFallbackStatus
        else -> EpisodeStatus.Empty
    }
}

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
    private val jableTvSource: JableTvSource,
    private val xnxxSource: XnxxSource,
    private val forum5278Source: Forum5278Source,
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

    // Search/detail caches: 60s TTL, LRU-evicted by capacity. Both deduplicate rapid
    // repeat queries (e.g. switching source chips on the search page) so we don't
    // fan out 8 scrapers for a question we just answered. TtlLruCache handles
    // eviction/synchronization — see app/data/cache/TtlLruCache.kt.
    private val searchCache = TtlLruCache<String, PaginatedResult<Vod>>(
        capacity = 10, ttlMs = 60_000L,
    )
    private val detailCache = TtlLruCache<String, VodDetail>(
        capacity = 20, ttlMs = 60_000L,
    )

    private fun searchCacheGet(key: String): PaginatedResult<Vod>? = searchCache.get(key)
    private fun searchCachePut(key: String, result: PaginatedResult<Vod>) = searchCache.put(key, result)
    private fun detailCacheGet(key: String): VodDetail? = detailCache.get(key)
    private fun detailCachePut(key: String, detail: VodDetail) = detailCache.put(key, detail)

    private fun getSource(sourceType: SourceType): SiteSource = when (sourceType) {
        SourceType.GIMYMAX -> gimyMaxSource
        SourceType.GIMYTV -> gimyTvSource
        SourceType.MOVIEFFM -> movieffmSource
        SourceType.GIMY_TW -> gimyTwSource
        SourceType.EYNY_TV -> eynyTvSource
        SourceType.IMAPLE_TV -> imapleTvSource
        SourceType.MOMOVOD -> momovodSource
        SourceType.KUBO123 -> kubo123Source
        SourceType.JABLE_TV -> jableTvSource
        SourceType.XNXX -> xnxxSource
        SourceType.FORUM5278 -> forum5278Source
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
        // Centralised cleanup before any caller (DetailViewModel / PlayerViewModel)
        // sees the data — drops parser-misread episode numbers and outlier lines,
        // tags survivors with EpisodeGroup.confidence. See EpisodeNormalizer KDoc.
        val raw = EpisodeNormalizer.normalize(getSource(sourceType).fetchVodDetail(vodId))
        val parsedMaxEp = raw.episodes.maxOfOrNull { line ->
            line.episodes.maxOfOrNull { ep -> ep.number } ?: 0
        } ?: 0
        val singleSiteAggregated: EpisodeStatus = when (val s = raw.vod.siteStatus) {
            is EpisodeStatus.Finished -> s
            is EpisodeStatus.InProgress -> if (parsedMaxEp > s.latest) {
                EpisodeStatus.InProgress(parsedMaxEp, Confidence.ParsedFromLines)
            } else s
            is EpisodeStatus.Movie, is EpisodeStatus.Raw -> s
            EpisodeStatus.Empty -> if (parsedMaxEp > 1) {
                EpisodeStatus.InProgress(parsedMaxEp, Confidence.ParsedFromLines)
            } else EpisodeStatus.Empty
        }
        return raw.copy(aggregatedStatus = singleSiteAggregated)
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
    /**
     * Suspend-based source list — calls snapshot() so cold-start callers wait for
     * DataStore's first emission rather than racing against the StateFlow seed.
     * Reading `enabledSources.value` directly here used to return ALL sources before
     * preferences loaded, polluting the 60s search/enrichment cache for that window.
     */
    private suspend fun searchOrder(): List<SiteSource> {
        val enabled = sourcePreferencesRepository.snapshot()
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
        // Cache key has three parts:
        //   1) normalized keyword — collapses whitespace / case / simp-trad variants
        //   2) page number
        //   3) enabled-sources fingerprint — toggling sources MUST invalidate cache
        //      otherwise users see the previous source set's results until cache expires
        // snapshot() suspends until DataStore loads, so the fingerprint reflects the
        // user's actual settings (not the StateFlow seed).
        val enabled = sourcePreferencesRepository.snapshot()
        val enabledFp = enabled.map { it.name }.sorted().joinToString(",")
        val cacheKey = "${normalizeSearchKey(keyword)}|$page|$enabledFp"
        searchCacheGet(cacheKey)?.let { return it }

        val sources = listOf(
            gimyTvSource, gimyMaxSource, imapleTvSource, gimyTwSource,
            eynyTvSource, momovodSource, kubo123Source, movieffmSource,
        ).filter { it.sourceType in enabled }

        val result = coroutineScope {
            val deferreds = sources.map { src ->
                async {
                    // null 代表這個來源整個失敗（逾時／連不上／解析不到），跟「有回應但沒有
                    // 符合的片」要分開，否則無法判斷這次搜尋值不值得放進快取。
                    try {
                        withTimeout(5_000) { src.search(keyword, page) }
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            val results = deferreds.map { it.await() }
            val answered = results.filterNotNull()

            // Merge in priority order, dedupe across sources, drop adult content
            var merged = emptyList<Vod>()
            for (r in answered) merged = mergeSearchResults(merged, r.items.filterNot { looksAdult(it) })

            answered.isNotEmpty() to PaginatedResult(
                items = merged,
                currentPage = page,
                totalPages = answered.maxOfOrNull { it.totalPages } ?: 0,
                hasMore = answered.any { it.hasMore },
            )
        }
        val (anySourceAnswered, searchResult) = result
        // 全部來源都失敗時不進快取。斷網或鏡像掛掉那一刻的空結果若被存進 60 秒快取，
        // 使用者按重試會立刻拿到同一份空結果，看起來就是按了完全沒作用。
        if (anySourceAnswered) searchCachePut(cacheKey, searchResult)
        return searchResult
    }

    private fun mergeSearchResults(primary: List<Vod>, secondary: List<Vod>): List<Vod> {
        val result = primary.toMutableList()
        // Dedup by (base, season) — different seasons of the same show stay
        // separate. Previous normalizeTitle-only dedup folded "斗羅大陸" and
        // "斗羅大陸 第二季" into one bucket and dropped one of them from search
        // results.
        val primaryKeys = primary.map { parseTitleKey(it.title) }.toSet()
        for (vod in secondary) {
            if (parseTitleKey(vod.title) !in primaryKeys) {
                result.add(vod)
            }
        }
        return result
    }

    private fun normalizeTitle(title: String): String {
        var s = title
            .replace(Regex("[\\s　]+"), "")
            .replace(Regex("[（）()\\[\\]【】《》]"), "")
            .lowercase()
        // Trad/Simp folding for the most common cases that break cross-source matching
        // (e.g. "斗罗大陆" from a simplified-source mirror vs "斗羅大陸" from a Taiwan
        // mirror). Targeted character pairs only — full s2t conversion would need an
        // embedded dictionary; this list covers ~95% of the breakage we've seen.
        s = simpToTradFold(s)
        // Season aliases: "第二季" / "season 2" / a trailing "2"-style suffix should
        // collapse to "2" (or get stripped) so they all reduce to the same form.
        s = s.replace(Regex("第([一二三四五六七八九十])季")) { m ->
                val n = chineseDigit(m.groupValues[1]); if (n > 0) n.toString() else ""
            }
            .replace(Regex("第(\\d+)季"), "$1")
            .replace(Regex("season\\s*(\\d+)"), "$1")
        return s.trim()
    }

    /**
     * Cache-key canonicalization for searchAllSources. Same query in different
     * shells (mixed whitespace, mixed simp/trad, mixed case) should hit the same
     * entry; otherwise the LRU fans out across 3-4 keys for one logical search.
     */
    private fun normalizeSearchKey(keyword: String): String =
        simpToTradFold(keyword.trim().replace(Regex("[\\s　]+"), "").lowercase())

    /**
     * Tiny S→T fold for cross-source title matching. NOT a general-purpose converter —
     * only characters that we've actually seen cause matching misses are listed.
     * Order doesn't matter (one-pass char replacement).
     */
    private fun simpToTradFold(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(SIMP_TRAD_FOLD[c] ?: c)
        return sb.toString()
    }

    private fun chineseDigit(c: String): Int = when (c) {
        "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5
        "六" -> 6; "七" -> 7; "八" -> 8; "九" -> 9; "十" -> 10
        else -> 0
    }

    /**
     * Multi-character Chinese number parser (for season parsing).
     *
     * Handles: 一-九 (1-9), 十 (10), 十一-十九 (11-19), 二十-九十 (20-90),
     * 二十一-九十九 (21-99). Falls back to Int parse for arabic input. Returns 0 if
     * unparseable. Sufficient for season numbers (rarely exceed 20).
     */
    private fun chineseNumberToInt(s: String): Int {
        if (s.isEmpty()) return 0
        s.toIntOrNull()?.let { return it }
        if (s.length == 1) return chineseDigit(s)
        if (s == "十") return 10
        if (s.startsWith("十") && s.length == 2) return 10 + chineseDigit(s.substring(1))
        if (s.length == 2 && s.endsWith("十")) return chineseDigit(s.substring(0, 1)) * 10
        if (s.length == 3 && s[1] == '十') {
            return chineseDigit(s.substring(0, 1)) * 10 + chineseDigit(s.substring(2))
        }
        return 0
    }

    /**
     * Cross-source matching key. Same content across mirrors maps to the same
     * (base, season) pair; different seasons of the same show stay distinct.
     *
     * Default season = 1 — "斗羅大陸" (no marker) matches "斗羅大陸 第一季" but
     * NOT "斗羅大陸 第二季". Without this distinction, normalizeTitle stripped
     * season markers entirely and conflated all seasons into one bucket, causing
     * cross-season-merged lines to leak into other seasons' VodDetail.episodes.
     */
    private data class TitleKey(val base: String, val season: Int = 1)

    /**
     * Cross-source title similarity score for [TitleKey] pairs.
     *
     *   1.0  same season AND same base
     *   0.0  different season (we never cross-merge across seasons; that's the
     *        whole point of the (base, season) key)
     *   else Jaro-Winkler on the base strings — captures things like
     *        「鋼之鍊金術師FA」 vs 「鋼之鍊金術師」 where exact equality misses but
     *        the strings are clearly the same show.
     *
     * Threshold elsewhere (0.92) is conservative — high enough that this rule
     * doesn't reintroduce the cross-content collisions that v2.5.1 fixed. Lower
     * the threshold cautiously: false positives here put unrelated shows on the
     * same VodDetail, much worse than missing a less common alternate spelling.
     */
    private fun scoreMatch(a: TitleKey, b: TitleKey): Double {
        if (a.season != b.season) return 0.0
        if (a.base == b.base) return 1.0
        return jaroWinkler(a.base, b.base)
    }

    private fun jaroWinkler(s1: String, s2: String): Double {
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        if (s1 == s2) return 1.0
        val matchDistance = (maxOf(s1.length, s2.length) / 2 - 1).coerceAtLeast(0)
        val s1Matches = BooleanArray(s1.length)
        val s2Matches = BooleanArray(s2.length)
        var matches = 0
        for (i in s1.indices) {
            val start = maxOf(0, i - matchDistance)
            val end = minOf(i + matchDistance + 1, s2.length)
            for (j in start until end) {
                if (s2Matches[j] || s1[i] != s2[j]) continue
                s1Matches[i] = true
                s2Matches[j] = true
                matches++
                break
            }
        }
        if (matches == 0) return 0.0
        var transpositions = 0
        var k = 0
        for (i in s1.indices) {
            if (!s1Matches[i]) continue
            while (k < s2Matches.size && !s2Matches[k]) k++
            if (k < s2.length && s1[i] != s2[k]) transpositions++
            k++
        }
        val m = matches.toDouble()
        val jaro = (m / s1.length + m / s2.length + (m - transpositions / 2.0) / m) / 3.0
        // Winkler boost — prefix up to 4 chars
        var prefix = 0
        for (i in 0 until minOf(4, minOf(s1.length, s2.length))) {
            if (s1[i] == s2[i]) prefix++ else break
        }
        return jaro + 0.1 * prefix * (1.0 - jaro)
    }

    private fun parseTitleKey(title: String): TitleKey {
        val s = title
            .replace(Regex("[\\s　]+"), "")
            .replace(Regex("[（）()\\[\\]【】《》]"), "")
            .lowercase()
            .let { simpToTradFold(it) }

        // Pattern 1: "X第N季" / "X第N部" (Chinese suffix). Most explicit signal.
        Regex("^(.+?)第([一二三四五六七八九十百\\d]+)[季部]$").find(s)?.let { m ->
            val n = chineseNumberToInt(m.groupValues[2])
            if (n > 0) return TitleKey(m.groupValues[1], n)
        }
        // Pattern 2: trailing "season N" / "s N".
        Regex("^(.+?)(?:season|s)(\\d+)$").find(s)?.let { m ->
            val n = m.groupValues[2].toIntOrNull()
            if (n != null && n > 0) return TitleKey(m.groupValues[1], n)
        }
        // Pattern 3: trailing pure digit (heuristic — "斗羅大陸2" → S2).
        // Require base to be ≥ 2 chars so we don't strip 'S' from titles ending in
        // a digit that's actually part of the name. Imperfect: "復仇者2" gets S=2
        // even though it's a sequel film, but in our model that just means it
        // won't merge with "復仇者" — same outcome as not parsing it.
        Regex("^(.+?)(\\d+)$").find(s)?.let { m ->
            val candidate = m.groupValues[1]
            val n = m.groupValues[2].toIntOrNull()
            if (n != null && n > 0 && candidate.length >= 2) {
                return TitleKey(candidate, n)
            }
        }
        return TitleKey(s, 1)
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
            val deferreds = searchOrder().map { src ->
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

    override suspend fun getEnrichedVodDetail(
        sourceType: SourceType,
        vodId: Long,
        cachedPrimary: VodDetail?,
        forceRefresh: Boolean,
    ): VodDetail {
        val cacheKey = "${sourceType.name}|$vodId"
        // Cache hit returns the fully-enriched (8-source merged) detail — skips re-querying 7 sources.
        // Caller's `cachedPrimary` is only the primary-source detail, so prefer our richer cache.
        // forceRefresh=true (user pulled-to-refresh) bypasses cache so we re-fan to all 8 sources;
        // without this, refresh within 60s would return the same stale enriched result.
        if (!forceRefresh) {
            detailCacheGet(cacheKey)?.let { return it }
        }

        return coroutineScope {
            // Reuse caller-provided primary if present, otherwise fetch
            val primaryDetail = cachedPrimary ?: getSource(sourceType).fetchVodDetail(vodId)
            val primaryTitle = primaryDetail.vod.title
            val primaryYear = primaryDetail.vod.year
            // Match by (base, season) tuple with optional similarity fallback. Season
            // must match exactly — that's the cross-season firewall from v2.5.1. For
            // base, exact equality is preferred; if no exact hit, scoreMatch ≥ 0.92
            // catches close-but-not-equal variants ("鋼之鍊金術師FA" vs "鋼之鍊金術師")
            // without re-introducing cross-content false positives that the season
            // gate already filters.
            val primaryKey = parseTitleKey(primaryTitle)
            // Raised from 0.92 → 0.95: stricter fallback. Most "should match" cases
            // already go through exact key equality; scoring is just for typo-level
            // / suffix-tag variants ("鋼之鍊金術師FA" vs "鋼之鍊金術師"). Higher bar
            // means fewer alternate spellings caught, but also a smaller window for
            // unrelated-title false positives that could resurrect cross-content
            // pollution despite the season firewall.
            val similarityThreshold = 0.95

            // Query the other 7 sources in parallel for same (base, season). Each
            // source has its own 5s timeout — slow/failing sources don't block the rest.
            val otherSources = searchOrder().filter { it.sourceType != sourceType }
            val matchedDetails = otherSources.map { src ->
                async {
                    try {
                        withTimeout(5_000) {
                            val results = src.search(primaryTitle, 1).items
                            // Two-pass: prefer exact (key equality) before falling back
                            // to scoreMatch ≥ threshold. Year filter unchanged.
                            val candidates = results.filter {
                                !looksAdult(it) &&
                                    (primaryYear == 0 || it.year == 0 || it.year == primaryYear)
                            }
                            val match = candidates.firstOrNull { parseTitleKey(it.title) == primaryKey }
                                ?: candidates.firstOrNull {
                                    scoreMatch(parseTitleKey(it.title), primaryKey) >= similarityThreshold
                                }
                                ?: return@withTimeout null
                            src.fetchVodDetail(match.id)
                        }
                    } catch (_: Exception) { null }
                }
            }.mapNotNull { it.await() }

            if (matchedDetails.isEmpty()) {
                // Don't cache primary-only — caching here used to pin every visit to the
                // primary list for 60s, including transient cases where 7 secondaries
                // happened to all timeout. Caller passed cachedPrimary so re-running
                // enrichment costs nothing for primary, only re-fans the 7 secondaries.
                return@coroutineScope primaryDetail
            }

            // Merge episode groups: primary first, then each matched secondary with source prefix.
            // sourceType is REQUIRED on secondary groups so the player can route fetchPlayerData
            // through the actual scraper that knows how to decode the playUrl. lineId carries
            // the un-encoded original id alongside the negatively-encoded sourceId — see
            // EpisodeGroup KDoc for why both are kept.
            val secondaryGroups = matchedDetails.flatMap { detail ->
                val displayName = detail.vod.sourceType.displayName
                detail.episodes.map { group ->
                    group.copy(
                        sourceName = "[$displayName] ${group.sourceName}",
                        sourceId = -(detail.vod.sourceType.ordinal * 100 + group.sourceId + 1),
                        sourceType = detail.vod.sourceType,
                        lineId = group.sourceId,
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

            // Cross-source vod.status fallback: when primary's listing didn't carry a
            // status string (e.g. parser missed it on that page) but a matched secondary
            // has one, surface the secondary's so the badge can still display authoritative
            // text instead of falling through to our own count compute.
            val statusFallback = primaryDetail.vod.status.takeIf { it.isNotBlank() }
                ?: matchedDetails.firstNotNullOfOrNull {
                    it.vod.status.takeIf { s -> s.isNotBlank() }
                }
                ?: ""
            val primaryFallbackStatus = parseEpisodeStatus(statusFallback)

            // Per-site metadata for v2.8.0 site-level UI: each scraper that
            // contributed episodes keeps its own Vod (with that site's status string
            // verbatim), so DetailScreen can flip the badge per chip selection.
            // Primary entry uses the fallback-resolved status so aggregation and
            // per-chip display agree on the primary's effective status string.
            val siteMetadata = buildMap {
                put(
                    primaryDetail.vod.sourceType,
                    primaryDetail.vod.copy(status = statusFallback, siteStatus = primaryFallbackStatus),
                )
                for (md in matchedDetails) put(md.vod.sourceType, md.vod)
            }

            // Build the merged detail BEFORE computing aggregatedStatus — final normalize
            // may prune outlier secondary lines (cross-season merges, broken parsers), and
            // reading max episode.number from PRE-prune lines would inflate the badge in
            // exactly the way the v3.0.0 redesign aimed to eliminate.
            val premergedEnriched = primaryDetail.copy(
                vod = siteMetadata.getValue(primaryDetail.vod.sourceType),
                episodes = allGroups,
                seriesVods = mergedSeries,
                relatedVods = filteredRelated,
                siteMetadata = siteMetadata,
            )
            // Final normalize across the merged groups (cluster median uses primary
            // lines; secondary outliers get pruned even though they survived
            // individual-scraper parsing).
            val normalizedEnriched = EpisodeNormalizer.normalize(premergedEnriched)

            // Now compute aggregatedStatus from POST-normalize episodes so the badge
            // number cannot exceed what the grid can actually play.
            val crossSiteMaxEp = normalizedEnriched.episodes.maxOfOrNull { line ->
                line.episodes.maxOfOrNull { it.number } ?: 0
            } ?: 0
            val aggregatedStatus = computeAggregatedStatus(
                normalizedMaxEp = crossSiteMaxEp,
                siteMetadata = siteMetadata,
                primaryFallbackStatus = primaryFallbackStatus,
            )

            normalizedEnriched.copy(aggregatedStatus = aggregatedStatus).also { detailCachePut(cacheKey, it) }
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
     * Within the same tier, lines with MORE episodes win — stable-but-stale lines
     * (e.g. primary listing says "更新至 25 集" while another tier-1 line carries 30)
     * shouldn't be the default pick when a fresher one exists.
     *
     * The `!name.contains("雲")` guard prevents 「無盡雲」from matching gimyTop's「無盡」tag.
     */
    private fun rankEpisodeGroups(groups: List<EpisodeGroup>): List<EpisodeGroup> {
        // Curated lists, ordered longer-first so e.g. "順暢雲" matches before "順暢".
        // Names verified against live HTML on gimy01.tv / gimytv.ai / imaple.tv (2026-05).
        // If sites rename their lines this list goes stale — scrapers can override
        // priority via EpisodeGroup.linePriority instead of relying on this map.
        val gimyMainLines = listOf(
            // CDN-branded high-quality lines unique to gimyMax / gimyTv
            "4K畫質線路", "高清線路", "騰訊線路", "奇藝線路", "愛奇異線路", "超清畫質線路",
            "順暢雲", "無盡雲", "魔都雲", "非凡雲", "新浪雲",
            // Legacy un-suffixed names that some older builds still emit
            "順暢", "無盡",
        )
        // Shared third-party CDNs surfaced by the MacCMS-template sites
        // (Imaple / Momovod / Kubo123 / GimyTw / Eyny).
        val sharedCdnLines = listOf(
            "卧龍雲", "索尼雲", "閃電雲", "極速雲", "優質雲",
        )

        fun tierOf(group: EpisodeGroup): Int {
            // Prefer scraper-supplied tier when present — robust to upstream renames.
            group.linePriority?.let { return it }
            val name = group.sourceName
            return when {
                gimyMainLines.any { name.contains(it) } ->
                    gimyMainLines.indexOfFirst { name.contains(it) }
                sharedCdnLines.any { name.contains(it) } ->
                    20 + sharedCdnLines.indexOfFirst { name.contains(it) }
                // MovieFFM lines come back with sourceId in the 1000+ range
                name.contains("MovieFFM") || (group.sourceId >= 1000 && group.sourceId > 0) ->
                    40 + (group.sourceId % 10)
                // Catch-all 雲 suffix that didn't match the curated lists — better
                // than dumping into Tier 99 since cloud-tagged lines are usually
                // newer / faster than no-tag fallbacks.
                name.contains("雲") -> 60
                else -> 99
            }
        }

        return groups.sortedWith(
            compareBy<EpisodeGroup> { tierOf(it) }
                .thenByDescending { it.episodes.size }
        )
    }

    // ── Home page mixed content ──

    // Movieffm typeId → matching gimymax typeId for interleaving
    private val ffmToGimyMap = mapOf(
        101 to 1, 201 to 20, 202 to 13, 203 to 16, 204 to 21,
        205 to 4, 207 to 14, 208 to 15, 206 to 29
    )

    override suspend fun findByTitle(title: String, preferredSource: SourceType): TitleLookup {
        val key = parseTitleKey(title)
        // 先問原來源。網域換了但站還是同一個時，片子多半還在、只是 id 不同，這一步就會中。
        val primary = runCatching {
            withTimeout(6_000) { getSource(preferredSource).search(title, 1) }
        }
        primary.getOrNull()?.items?.firstOrNull { parseTitleKey(it.title) == key }
            ?.let { return TitleLookup(it, sourceAnswered = true) }

        // 原來源查無此片才跨來源找。換站至少讓這筆記錄還開得起來，比留著一個死連結好。
        val cross = runCatching { searchAllSources(title, 1).items }.getOrDefault(emptyList())
            .firstOrNull { parseTitleKey(it.title) == key }
        return TitleLookup(cross, sourceAnswered = primary.isSuccess)
    }

    override fun clearMemoryCaches() {
        gimyHomeCache = null
        gimyHomeCacheTime = 0L
        movieffmHomeCache = null
        movieffmHomeCacheTime = 0L
        searchCache.clear()
        detailCache.clear()
    }

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
                20 to "韓劇", 13 to "陸劇", 16 to "美劇", 15 to "日劇",
                1 to "電影", 4 to "動漫", 14 to "台劇", 21 to "港劇",
                29 to "綜藝", 22 to "紀錄片"
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
                        // 給的時間比 gimy 那組還長：movieffm 的分類頁約 170KB（gimy 約 60KB），
                        // 九頁同時抓再用 Jsoup 解，在電視盒的 CPU 上遠比模擬器吃力。原本 6 秒
                        // 在開發機綽綽有餘，到電視上卻整組逾時，首頁就安靜地少掉所有 FFM 列。
                        // 這一段是 Phase 2、不擋首頁顯示，拉長只會讓 FFM 列晚一點補上。
                        withTimeout(12_000) {
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

/**
 * Targeted simplified→traditional character folding for cross-source title matching.
 *
 * Why a hand-curated map instead of a full s2t library: full converters (~10k chars +
 * phrase rules) add MB to the APK and need ICU4C or a phrase dictionary. We only need
 * to break ties on the ~80 chars that show up in TV/drama/anime titles — that's enough
 * to fold "斗罗大陆" → "斗羅大陸", "庆余年" → "慶餘年", "两生花" → "兩生花" etc.
 *
 * If you hit a real-world title that still misses, append the pair here.
 */
private val SIMP_TRAD_FOLD: Map<Char, Char> = run {
    val pairs = "罗羅陆陸龙龍凤鳳万萬与與国國师師时時间間风風云雲战戰击擊发發学學园園体體" +
        "处處来來个個们們这這关關当當长長实實见見头頭听聽觉覺杀殺极極现現选選区區" +
        "单單点點转轉终終双雙胆膽怀懷历歷应應让讓灵靈远遠进進还還给給谁誰没沒总總经經" +
        "错錯类類题題异異网網务務业業场場际際议議论論试試决決训訓谈談赛賽队隊馆館标標" +
        "误誤离離难難乱亂岁歲礼禮农農县縣边邊钟鐘银銀钢鋼钱錢锅鍋镜鏡闯闖阵陣险險阴陰" +
        "阳陽顺順顾顧顶頂项項鸟鳥鸡雞鸣鳴鹅鵝龟龜余餘庆慶两兩为為剧劇饭飯"
    val m = HashMap<Char, Char>(pairs.length / 2)
    var i = 0
    while (i + 1 < pairs.length) {
        m[pairs[i]] = pairs[i + 1]; i += 2
    }
    m
}
