package com.gimy.tv.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.gimy.tv.data.local.dao.*
import com.gimy.tv.data.local.entity.*

@Database(
    entities = [
        FavoriteEntity::class,
        WatchHistoryEntity::class,
        VodCacheEntity::class,
        SearchHistoryEntity::class,
        MovieffmSlugEntity::class,
        EmbedSlugEntity::class
    ],
    version = 6,
    // 匯出 schema JSON（app/schemas，有進版控）。之前關著，等於連寫 migration 測試的
    // 能力都沒有——漏寫一條要等使用者升級才會發現。注意 v1~v5 的 JSON 已經補不回來，
    // 所以能測的只有 6→7 之後。
    exportSchema = true
)
abstract class GimyDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun vodCacheDao(): VodCacheDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun movieffmSlugDao(): MovieffmSlugDao
    abstract fun embedSlugDao(): EmbedSlugDao
}
