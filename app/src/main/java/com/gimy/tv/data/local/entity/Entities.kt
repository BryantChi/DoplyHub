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
    val addedAt: Long = System.currentTimeMillis(),
    /** v2.3.0+ : split adult records from main flow. Migrated rows default to false. */
    val isAdult: Boolean = false,
)

@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vodId: Long,
    /** Primary scraper the user came from (the list/detail entry point). Stays
     *  the same regardless of which line actually played. */
    val sourceType: String,
    val title: String,
    val coverUrl: String,
    val episodeNum: Int,
    val episodeTitle: String,
    val sourceId: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis(),
    /** v2.3.0+ : split adult records from main flow. Migrated rows default to false. */
    val isAdult: Boolean = false,
    /** v2.5.2+ : The actual scraper whose line played, recorded so a "繼續觀看"
     *  flow can re-route to the same line on next visit (e.g. user came from
     *  GimyTv but watched on EnyTV's enriched line). Null = primary line was
     *  played; legacy rows pre-migration use null. */
    val playedSourceType: String? = null,
    /** v2.5.2+ : Episode kind marker (OAD/番外/特別篇/null=main). Null = main
     *  episode; legacy rows pre-migration use null. Once parsers populate this,
     *  watch progress for OAD won't collide with main episode at same number. */
    val episodeKind: String? = null,
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
    val contentType: String, // "movies" or "drama"
    /** v2.5.2+ : When the row was last (re)inserted. Lets us prune entries that
     *  haven't been touched in a long time so this table doesn't grow forever
     *  for users who browse heavily. Migrated rows default to 0 (treated as
     *  ancient by any prune query). */
    val cachedAt: Long = System.currentTimeMillis(),
)


/**
 * Reverse map for slug-based embed sources (jable / xnxx).
 *
 * Their detail URLs are slugs but Vod.id must be a Long, so the id is a one-way hash of the
 * slug. Favourites and history store only the id, so without this table the mapping dies
 * with the process and every saved entry fails to open after a restart.
 *
 * Composite key: two sources share this table and their ids come from independent hash
 * spaces, so a collision must not let one source read the other's slug.
 */
@Entity(tableName = "embed_slugs", primaryKeys = ["sourceType", "vodId"])
data class EmbedSlugEntity(
    val vodId: Long,
    val slug: String,
    val sourceType: String,
    /** Lets the existing startup prune drop rows untouched for a long time. */
    val cachedAt: Long = System.currentTimeMillis(),
)
