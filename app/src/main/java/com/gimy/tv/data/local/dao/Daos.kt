package com.gimy.tv.data.local.dao

import androidx.room.*
import com.gimy.tv.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites WHERE isAdult = 0 ORDER BY addedAt DESC")
    fun getAll(): Flow<List<FavoriteEntity>>

    /** Adult-only listing — surfaced inside the 18+ zone, never on the main FavoritesScreen. */
    @Query("SELECT * FROM favorites WHERE isAdult = 1 ORDER BY addedAt DESC")
    fun getAllAdult(): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE vodId = :vodId AND sourceType = :sourceType)")
    fun isFavorite(vodId: Long, sourceType: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE vodId = :vodId AND sourceType = :sourceType")
    suspend fun delete(vodId: Long, sourceType: String)

    @Query("SELECT * FROM favorites WHERE sourceType = :sourceType")
    suspend fun getBySource(sourceType: String): List<FavoriteEntity>

    @Query("UPDATE favorites SET title = :title WHERE vodId = :vodId AND sourceType = :sourceType")
    suspend fun updateTitle(vodId: Long, sourceType: String, title: String): Int

    @Query("UPDATE favorites SET missCount = :missCount WHERE vodId = :vodId AND sourceType = :sourceType")
    suspend fun updateMissCount(vodId: Long, sourceType: String, missCount: Int): Int

    @Query("SELECT missCount FROM favorites WHERE vodId = :vodId AND sourceType = :sourceType LIMIT 1")
    suspend fun missCountOf(vodId: Long, sourceType: String): Int?

    @Query("DELETE FROM favorites WHERE missCount > 0")
    suspend fun deleteStale(): Int

    @Query("SELECT COUNT(*) FROM favorites WHERE missCount > 0")
    fun staleCount(): Flow<Int>
}

@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history WHERE isAdult = 0 ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE isAdult = 0 ORDER BY updatedAt DESC LIMIT :limit")
    fun getRecent(limit: Int): Flow<List<WatchHistoryEntity>>

    /** Adult-only history — surfaced inside the 18+ zone only. */
    @Query("SELECT * FROM watch_history WHERE isAdult = 1 ORDER BY updatedAt DESC")
    fun getAllAdult(): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE isAdult = 1 ORDER BY updatedAt DESC LIMIT :limit")
    fun getRecentAdult(limit: Int): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE vodId = :vodId AND sourceType = :sourceType LIMIT 1")
    suspend fun getByVod(vodId: Long, sourceType: String): WatchHistoryEntity?

    @Query("SELECT * FROM watch_history WHERE sourceType = :sourceType")
    suspend fun getBySource(sourceType: String): List<WatchHistoryEntity>

    @Query("UPDATE watch_history SET title = :title WHERE vodId = :vodId AND sourceType = :sourceType")
    suspend fun updateTitle(vodId: Long, sourceType: String, title: String): Int

    @Query("UPDATE watch_history SET missCount = :missCount WHERE vodId = :vodId AND sourceType = :sourceType")
    suspend fun updateMissCount(vodId: Long, sourceType: String, missCount: Int): Int

    @Query("SELECT missCount FROM watch_history WHERE vodId = :vodId AND sourceType = :sourceType LIMIT 1")
    suspend fun missCountOf(vodId: Long, sourceType: String): Int?

    @Query("DELETE FROM watch_history WHERE vodId = :vodId AND sourceType = :sourceType")
    suspend fun deleteStaleRow(vodId: Long, sourceType: String): Int

    @Query("DELETE FROM watch_history WHERE missCount > 0")
    suspend fun deleteStale(): Int

    @Query("SELECT COUNT(*) FROM watch_history WHERE missCount > 0")
    fun staleCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(history: WatchHistoryEntity)

    @Query("DELETE FROM watch_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM watch_history WHERE vodId = :vodId AND sourceType = :sourceType")
    suspend fun deleteByVod(vodId: Long, sourceType: String)

    @Query("DELETE FROM watch_history")
    suspend fun deleteAll()

    @Query("DELETE FROM watch_history WHERE isAdult = 1")
    suspend fun deleteAllAdult()
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

@Dao
interface MovieffmSlugDao {
    @Query("SELECT slug FROM movieffm_slugs WHERE vodId = :vodId LIMIT 1")
    suspend fun getSlug(vodId: Long): String?

    @Query("SELECT contentType FROM movieffm_slugs WHERE vodId = :vodId LIMIT 1")
    suspend fun getContentType(vodId: Long): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(slugs: List<MovieffmSlugEntity>)

    @Query("SELECT COUNT(*) FROM movieffm_slugs")
    suspend fun count(): Int

    /** Drop slug rows whose cachedAt is older than [thresholdMs]. Used by a future
     *  periodic prune so this table doesn't grow without bound. Returns the
     *  number of rows removed. */
    @Query("DELETE FROM movieffm_slugs WHERE cachedAt < :thresholdMs")
    suspend fun pruneOlderThan(thresholdMs: Long): Int
}


@Dao
interface EmbedSlugDao {
    @Query("SELECT slug FROM embed_slugs WHERE vodId = :vodId AND sourceType = :sourceType LIMIT 1")
    suspend fun getSlug(vodId: Long, sourceType: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<EmbedSlugEntity>)

    @Query("DELETE FROM embed_slugs WHERE cachedAt < :thresholdMs")
    suspend fun pruneOlderThan(thresholdMs: Long): Int
}
