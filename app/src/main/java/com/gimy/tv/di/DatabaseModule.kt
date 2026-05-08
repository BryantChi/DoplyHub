package com.gimy.tv.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `movieffm_slugs` (
                    `vodId` INTEGER NOT NULL PRIMARY KEY,
                    `slug` TEXT NOT NULL,
                    `contentType` TEXT NOT NULL
                )
            """.trimIndent())
        }
    }

    /** v2.3.0 — segregate adult records from the main history/favorites flow.
     *  Existing rows default to isAdult = 0 (false). */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `favorites` ADD COLUMN `isAdult` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `watch_history` ADD COLUMN `isAdult` INTEGER NOT NULL DEFAULT 0")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): GimyDatabase {
        return Room.databaseBuilder(
            context,
            GimyDatabase::class.java,
            "gimy_tv.db"
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
         .fallbackToDestructiveMigration()
         .build()
    }

    @Provides
    fun provideFavoriteDao(db: GimyDatabase): FavoriteDao = db.favoriteDao()

    @Provides
    fun provideWatchHistoryDao(db: GimyDatabase): WatchHistoryDao = db.watchHistoryDao()

    @Provides
    fun provideVodCacheDao(db: GimyDatabase): VodCacheDao = db.vodCacheDao()

    @Provides
    fun provideSearchHistoryDao(db: GimyDatabase): SearchHistoryDao = db.searchHistoryDao()

    @Provides
    fun provideMovieffmSlugDao(db: GimyDatabase): MovieffmSlugDao = db.movieffmSlugDao()
}
