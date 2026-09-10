package com.gimy.tv.domain.repository

import com.gimy.tv.domain.model.*
import kotlinx.coroutines.flow.Flow

data class HomeRowData(
    val title: String,
    val sourceType: SourceType,
    val typeId: Int,
    val items: List<Vod>
)

interface VodRepository {
    suspend fun getCategories(sourceType: SourceType): List<Category>
    suspend fun getVodList(sourceType: SourceType, typeId: Int, page: Int): PaginatedResult<Vod>
    suspend fun getVodDetail(sourceType: SourceType, vodId: Long): VodDetail
    suspend fun getPlayerData(sourceType: SourceType, episodeUrl: String): PlayerData
    suspend fun search(sourceType: SourceType, keyword: String, page: Int): PaginatedResult<Vod>

    // Series search
    suspend fun searchSeriesVods(vod: Vod): List<Vod>

    // Multi-source integration
    suspend fun searchAllSources(keyword: String, page: Int): PaginatedResult<Vod>
    suspend fun getEnrichedVodDetail(
        sourceType: SourceType,
        vodId: Long,
        cachedPrimary: VodDetail? = null,
        /** Bypass the in-memory detail cache when set. Used by user-initiated refresh
         *  so a stale 60s-cached result doesn't shadow the fresh fetch. */
        forceRefresh: Boolean = false,
    ): VodDetail
    suspend fun getGimyHomeRows(forceRefresh: Boolean = false): List<HomeRowData>
    suspend fun getMovieffmHomeRows(forceRefresh: Boolean = false): List<HomeRowData>

    /** 丟掉記憶體內的首頁／搜尋／詳情快取。清除快取與重試路徑用，讓下一次取用真的重打。 */
    fun clearMemoryCaches()

    /**
     * 依片名找出同一部片，用於舊 id 失效後的復原。
     *
     * 會分別回報「有沒有找到」與「[preferredSource] 這次到底有沒有回應」，因為呼叫端
     * 要靠後者分辨「這部片真的沒了」與「只是連不上」。
     */
    suspend fun findByTitle(title: String, preferredSource: SourceType): TitleLookup
}

interface FavoriteRepository {
    /** Main favorites list — never includes adult records (JABLE_TV / XNXX / FORUM5278). */
    fun getFavorites(): Flow<List<Vod>>
    /** Adult-only favorites list — surfaced inside the 18+ zone only. */
    fun getAdultFavorites(): Flow<List<Vod>>
    fun isFavorite(vodId: Long, sourceType: SourceType): Flow<Boolean>
    suspend fun addFavorite(vod: Vod)
    suspend fun removeFavorite(vodId: Long, sourceType: SourceType)
}

interface WatchHistoryRepository {
    /** Main recent list — never includes adult records. */
    fun getRecentHistory(limit: Int = 20): Flow<List<WatchHistoryEntry>>
    /** Adult-only recent list — surfaced inside the 18+ zone only. */
    fun getRecentAdultHistory(limit: Int = 50): Flow<List<WatchHistoryEntry>>
    suspend fun getProgress(vodId: Long, sourceType: SourceType): WatchHistoryEntry?
    suspend fun saveProgress(entry: WatchHistoryEntry)
    suspend fun deleteEntry(vodId: Long, sourceType: SourceType)
    suspend fun clearHistory()
    suspend fun clearAdultHistory()
}

/** [VodRepository.findByTitle] 的結果。[sourceAnswered] 為 false 代表原來源這次沒回應，
 *  找不到不能當成「這部片不存在」。 */
data class TitleLookup(val match: Vod?, val sourceAnswered: Boolean)

data class WatchHistoryEntry(
    val vodId: Long,
    /** Primary scraper the user came from (where they entered detail/list). */
    val sourceType: SourceType,
    val title: String,
    val coverUrl: String,
    val episodeNum: Int,
    val episodeTitle: String,
    val sourceId: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis(),
    /** Actual scraper whose line played (may differ from [sourceType] when fallback /
     *  user-switch lands on a cross-source enriched line). null = primary line played
     *  or pre-v2.5.3 row that never recorded this. Used by 「繼續觀看」 to route back to
     *  the right line on next visit. */
    val playedSourceType: SourceType? = null,
    /** Episode kind marker carried over from [Episode.kind]. null = main-line; pre-v2.5.4
     *  rows also use null. Persisted so progress for "OAD 5" doesn't silently overwrite
     *  progress for the regular ep5 (and vice versa). */
    val episodeKind: String? = null,
)

interface SearchHistoryRepository {
    fun getRecentSearches(limit: Int = 20): Flow<List<String>>
    suspend fun addSearch(keyword: String)
    suspend fun removeSearch(keyword: String)
    suspend fun clearSearches()
}
