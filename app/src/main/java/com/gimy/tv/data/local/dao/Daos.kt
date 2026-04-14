package com.gimy.tv.data.local.dao

import androidx.room.*
import com.gimy.tv.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun getAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE vodId = :vodId AND sourceType = :sourceType)")
    fun isFavorite(vodId: Long, sourceType: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE vodId = :vodId AND sourceType = :sourceType")
    suspend fun delete(vodId: Long, sourceType: String)
}

@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history ORDER BY updatedAt DESC LIMIT :limit")
    fun getRecent(limit: Int): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE vodId = :vodId AND sourceType = :sourceType LIMIT 1")
    suspend fun getByVod(vodId: Long, sourceType: String): WatchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(history: WatchHistoryEntity)

    @Query("DELETE FROM watch_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM watch_history WHERE vodId = :vodId AND sourceType = :sourceType")
    suspend fun deleteByVod(vodId: Long, sourceType: String)

    @Query("DELETE FROM watch_history")
    suspend fun deleteAll()
}

@Dao
interface VodCacheDao {
    @Query("SELECT * FROM vod_cache WHERE cacheKey = :key AND cachedAt > :minTime LIMIT 1")
    suspend fun get(key: String, minTime: Long): VodCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cache: VodCacheEntity)

    @Query("DELETE FROM vod_cache WHERE cachedAt < :maxAge")
    suspend fun deleteExpired(maxAge: Long)
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT :limit")
    fun getRecent(limit: Int = 20): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(search: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE keyword = :keyword")
    suspend fun delete(keyword: String)

    @Query("DELETE FROM search_history")
    suspend fun deleteAll()
}
