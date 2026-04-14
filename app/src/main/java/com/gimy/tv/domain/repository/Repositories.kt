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

    // Multi-source integration
    suspend fun searchAllSources(keyword: String, page: Int): PaginatedResult<Vod>
    suspend fun getEnrichedVodDetail(sourceType: SourceType, vodId: Long, cachedPrimary: VodDetail? = null): VodDetail
    suspend fun getGimyHomeRows(): List<HomeRowData>
    suspend fun getMovieffmHomeRows(): List<HomeRowData>
}

interface FavoriteRepository {
    fun getFavorites(): Flow<List<Vod>>
    fun isFavorite(vodId: Long, sourceType: SourceType): Flow<Boolean>
    suspend fun addFavorite(vod: Vod)
    suspend fun removeFavorite(vodId: Long, sourceType: SourceType)
}

interface WatchHistoryRepository {
    fun getRecentHistory(limit: Int = 20): Flow<List<WatchHistoryEntry>>
    suspend fun getProgress(vodId: Long, sourceType: SourceType): WatchHistoryEntry?
    suspend fun saveProgress(entry: WatchHistoryEntry)
    suspend fun deleteEntry(vodId: Long, sourceType: SourceType)
    suspend fun clearHistory()
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
