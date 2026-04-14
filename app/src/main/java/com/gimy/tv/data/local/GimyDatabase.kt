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
        SearchHistoryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class GimyDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun vodCacheDao(): VodCacheDao
    abstract fun searchHistoryDao(): SearchHistoryDao
}
