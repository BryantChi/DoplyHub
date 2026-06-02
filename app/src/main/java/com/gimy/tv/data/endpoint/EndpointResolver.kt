package com.gimy.tv.data.endpoint

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gimy.tv.data.update.SemVer
import com.gimy.tv.data.update.UpdateConfig
import com.gimy.tv.domain.model.SourceType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.endpointDataStore: DataStore<Preferences> by preferencesDataStore(name = "endpoint_resolver")

/**
 * Resolves per-source baseUrl from a remote endpoints.json (with hard-coded fallback).
 *
 * Why: Gimy mirror sites change domains frequently. Hard-coding baseUrl means re-releasing
 * the App for every domain change. This resolver fetches a small JSON from GitHub raw,
 * probes all candidate URLs in parallel, and picks the first responder by priority order
 * (so a primary site beats a faster mirror). Resolved URL is cached in DataStore (24h TTL).
 *
 * Flow:
 *   warmUp() — called from DoplyApp.onCreate; loads DataStore cache, then refreshIfStale.
 *   getBaseUrl(SourceType) — synchronous, returns current best URL (defaults if not yet resolved).
 *   forceRefreshAsync() — fire-and-forget; called by pull-to-refresh paths.
 */
@Singleton
class EndpointResolver @Inject constructor(
    @ApplicationContext context: Context,
    private val okHttpClient: OkHttpClient,
    private val sourcesProvider: javax.inject.Provider<Map<SourceType, @JvmSuppressWildcards com.gimy.tv.data.scraper.SiteSource>>,
) {
    companion object {
        private const val REMOTE_URL =
            "https://raw.githubusercontent.com/BryantChi/DoplyHub/dev/endpoints.json"
        private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000  // 24h
        private const val PROBE_TIMEOUT_MS = 3000L
        private const val FETCH_TIMEOUT_MS = 6000L
        // A healthy list page returns at least this many parseable items; tune if false-positives arise.
        private const val MIN_HEALTHY_ITEMS = 5

        // Hard-coded fallback for cold starts when remote JSON is unreachable.
        // Bump on each release so old installs still work.
        // Note: gimymax.com is intentionally excluded — as of 2026-05 it became a
        // "redirect announcement" page that responds 200 OK but serves no real content.
        private val DEFAULTS: Map<SourceType, List<String>> = mapOf(
            SourceType.GIMYMAX to listOf("https://gimy01.tv"),
            SourceType.GIMYTV to listOf("https://gimytv.ai"),
            SourceType.MOVIEFFM to listOf("https://www.movieffm.net"),
            SourceType.GIMY_TW to listOf("https://gimy.tw"),
            SourceType.EYNY_TV to listOf("https://eynytv.com"),
            SourceType.IMAPLE_TV to listOf("https://imaple.tv"),
            SourceType.MOMOVOD to listOf("https://momovod.app"),
            SourceType.KUBO123 to listOf("https://123kubo.net"),
            SourceType.JABLE_TV to listOf("https://jable.tv"),
            SourceType.XNXX to listOf("https://www.xnxx.com"),
            SourceType.FORUM5278 to listOf("https://5278.cc"),
        )

        private fun keyFor(type: SourceType) = stringPreferencesKey("url_${type.name}")
        private val LAST_REFRESH_KEY = longPreferencesKey("last_refresh_at")
    }

    private val dataStore = context.endpointDataStore
    private val refreshMutex = Mutex()
    private val internalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var resolved: Map<SourceType, String> = DEFAULTS.mapValues { it.value.first() }
    @Volatile
    private var lastRefreshAt: Long = 0L
    @Volatile
    private var cacheLoaded: Boolean = false
    @Volatile
    private var updateConfig: UpdateConfig = UpdateConfig.DEFAULT

    fun getBaseUrl(sourceType: SourceType): String =
        resolved[sourceType] ?: DEFAULTS[sourceType]!!.first()

    fun getUpdateConfig(): UpdateConfig = updateConfig

    /** Loads cached resolved URLs, then refreshes if 24h-stale. Called once from DoplyApp.onCreate. */
    suspend fun warmUp() {
        loadCache()
        refreshIfStale()
    }

    suspend fun refreshIfStale() {
        if (System.currentTimeMillis() - lastRefreshAt < CACHE_TTL_MS) return
        runCatching { refresh() }
    }

    /** Fire-and-forget refresh. Pull-to-refresh paths use this — current call uses cached URL,
     *  the next call sees the new URL once probing finishes. */
    fun forceRefreshAsync() {
        internalScope.launch { runCatching { refresh() } }
    }

    private suspend fun loadCache() {
        if (cacheLoaded) return
        runCatching {
            val prefs = dataStore.data.first()
            val updated = resolved.toMutableMap()
            SourceType.values().forEach { type ->
                prefs[keyFor(type)]?.takeIf { it.isNotBlank() }?.let { updated[type] = it }
            }
            resolved = updated
            lastRefreshAt = prefs[LAST_REFRESH_KEY] ?: 0L
        }
        cacheLoaded = true
    }

    private suspend fun refresh() = refreshMutex.withLock {
        val remoteConfig = fetchRemoteConfig()
        val newResolved = SourceType.values().associateWith { type ->
            val candidates = (remoteConfig?.get(type) ?: emptyList())
                .ifEmpty { DEFAULTS[type] ?: emptyList() }
            if (candidates.isEmpty()) return@associateWith resolved[type] ?: ""
            val source = sourcesProvider.get()[type]
            val healthy = if (source != null)
                pickHealthiest(candidates, MIN_HEALTHY_ITEMS) { url -> source.probeListCount(url) }
            else null
            healthy ?: pickBestEndpoint(candidates) ?: candidates.first()
        }
        resolved = newResolved
        lastRefreshAt = System.currentTimeMillis()
        runCatching {
            dataStore.edit { prefs ->
                newResolved.forEach { (type, url) -> prefs[keyFor(type)] = url }
                prefs[LAST_REFRESH_KEY] = lastRefreshAt
            }
        }
    }

    private suspend fun fetchRemoteConfig(): Map<SourceType, List<String>>? = withContext(Dispatchers.IO) {
        runCatching {
            withTimeout(FETCH_TIMEOUT_MS) {
                val req = Request.Builder().url(REMOTE_URL).get().build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withTimeout null
                    val body = resp.body?.string() ?: return@withTimeout null
                    parseConfig(body)
                }
            }
        }.getOrNull()
    }

    private fun parseConfig(json: String): Map<SourceType, List<String>>? = runCatching {
        val root = JSONObject(json)

        // Side effect: also extract update block (if present), update volatile updateConfig.
        // Kept in same parser to avoid a second HTTP fetch — same JSON serves both concerns.
        root.optJSONObject("update")?.let { upd ->
            updateConfig = UpdateConfig(
                minSupportedVersion = SemVer.parse(upd.optString("min_supported_version"))
                    ?: UpdateConfig.DEFAULT.minSupportedVersion,
                releaseRepo = upd.optString("release_repo")
                    .takeIf { it.isNotBlank() } ?: UpdateConfig.DEFAULT.releaseRepo,
                forceUpdateMessage = upd.optString("force_update_message")
                    .takeIf { it.isNotBlank() } ?: UpdateConfig.DEFAULT.forceUpdateMessage,
            )
        }

        // v2.4.0+ JSON layout splits adult sources into a separate `adult_endpoints` object
        // for visual / management isolation. We merge both objects here so the rest of the
        // resolver behaves as before. Earlier configs (single `endpoints` object) still work.
        val endpoints = root.optJSONObject("endpoints")
        val adultEndpoints = root.optJSONObject("adult_endpoints")
        if (endpoints == null && adultEndpoints == null) return@runCatching null
        val keyMap = mapOf(
            "gimymax" to SourceType.GIMYMAX,
            "gimytv" to SourceType.GIMYTV,
            "movieffm" to SourceType.MOVIEFFM,
            "gimy_tw" to SourceType.GIMY_TW,
            "eyny_tv" to SourceType.EYNY_TV,
            "imaple_tv" to SourceType.IMAPLE_TV,
            "momovod" to SourceType.MOMOVOD,
            "kubo123" to SourceType.KUBO123,
            "jable_tv" to SourceType.JABLE_TV,
            "xnxx" to SourceType.XNXX,
            "forum5278" to SourceType.FORUM5278,
        )
        val result = mutableMapOf<SourceType, List<String>>()
        keyMap.forEach { (key, type) ->
            // Look first in `endpoints`, then fall through to `adult_endpoints`.
            // (No conflict in practice — keys partition cleanly between the two objects.)
            val arr = endpoints?.optJSONArray(key) ?: adultEndpoints?.optJSONArray(key) ?: return@forEach
            val list = (0 until arr.length()).mapNotNull { idx ->
                arr.optString(idx, "").trim().takeIf { it.isNotBlank() }
            }
            if (list.isNotEmpty()) result[type] = list
        }
        result.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Probe all candidates in parallel; among successful ones, return first by priority order.
     *  Returns null if every probe failed/timed out — caller falls back to first candidate. */
    private suspend fun pickBestEndpoint(candidates: List<String>): String? = coroutineScope {
        val successful = candidates.map { url ->
            async(Dispatchers.IO) {
                runCatching {
                    withTimeout(PROBE_TIMEOUT_MS) {
                        val req = Request.Builder().url(url).head().build()
                        okHttpClient.newCall(req).execute().use { resp ->
                            url.takeIf { resp.isSuccessful }
                        }
                    }
                }.getOrNull()
            }
        }.awaitAll().toSet()
        candidates.firstOrNull { it in successful }
    }
}

/** Pure selection: returns the first candidate (by priority order) whose probe count >= minItems;
 *  null if none qualify. A probe returning -1 (unsupported) or 0 (broken) counts as unhealthy.
 *  Extracted for unit testing. */
internal suspend fun pickHealthiest(
    candidates: List<String>,
    minItems: Int,
    probe: suspend (String) -> Int,
): String? {
    for (url in candidates) {
        val count = runCatching { probe(url) }.getOrDefault(-1)
        if (count >= minItems) return url
    }
    return null
}
