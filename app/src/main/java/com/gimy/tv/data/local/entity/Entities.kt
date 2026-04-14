package com.gimy.tv.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vodId: Long,
    val sourceType: String,
    val title: String,
    val coverUrl: String,
    val category: String,
    val year: Int,
    val status: String,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vodId: Long,
    val sourceType: String,
    val title: String,
    val coverUrl: String,
    val episodeNum: Int,
    val episodeTitle: String,
    val sourceId: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "vod_cache")
data class VodCacheEntity(
    @PrimaryKey val cacheKey: String,
    val jsonData: String,
    val cachedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val keyword: String,
    val searchedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "movieffm_slugs")
data class MovieffmSlugEntity(
    @PrimaryKey val vodId: Long,
    val slug: String,
    val contentType: String // "movies" or "drama"
)
