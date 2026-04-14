package com.gimy.tv.di

import android.content.Context
import androidx.room.Room
import com.gimy.tv.data.local.GimyDatabase
import com.gimy.tv.data.local.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): GimyDatabase {
        return Room.databaseBuilder(
            context,
            GimyDatabase::class.java,
            "gimy_tv.db"
        ).build()
    }

    @Provides
    fun provideFavoriteDao(db: GimyDatabase): FavoriteDao = db.favoriteDao()

    @Provides
    fun provideWatchHistoryDao(db: GimyDatabase): WatchHistoryDao = db.watchHistoryDao()

    @Provides
    fun provideVodCacheDao(db: GimyDatabase): VodCacheDao = db.vodCacheDao()

    @Provides
    fun provideSearchHistoryDao(db: GimyDatabase): SearchHistoryDao = db.searchHistoryDao()
}
