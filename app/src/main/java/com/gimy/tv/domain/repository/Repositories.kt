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

data class WatchHistoryEntry(
    val vodId: Long,
    val sourceType: SourceType,
    val title: String,
    val coverUrl: String,
    val episodeNum: Int,
    val episodeTitle: String,
    val sourceId: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis()
)

interface SearchHistoryRepository {
    fun getRecentSearches(limit: Int = 20): Flow<List<String>>
    suspend fun addSearch(keyword: String)
    suspend fun removeSearch(keyword: String)
    suspend fun clearSearches()
}
