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

    /**
     * v2.5.2 — schema scaffolding for cross-source play history + slug cache prune.
     *
     *  watch_history.playedSourceType: actual scraper whose line played (vs the
     *      primary the user entered from). Currently nullable / unwired — will
     *      let "繼續觀看" route to the same enriched line on next visit.
     *  watch_history.episodeKind: OAD / 番外 / 特別篇 / null=main. Currently
     *      nullable / unwired — will keep OAD progress separate from same-numbered
     *      main episode once parsers populate it.
     *  movieffm_slugs.cachedAt: when the row was last (re)inserted. Default 0
     *      for legacy rows so a future prune-older-than query treats them as
     *      ancient and rebuilds them on next visit. New inserts get the current
     *      time via the entity's Kotlin default.
     */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `watch_history` ADD COLUMN `playedSourceType` TEXT")
            db.execSQL("ALTER TABLE `watch_history` ADD COLUMN `episodeKind` TEXT")
            db.execSQL("ALTER TABLE `movieffm_slugs` ADD COLUMN `cachedAt` INTEGER NOT NULL DEFAULT 0")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): GimyDatabase {
        return Room.databaseBuilder(
            context,
            GimyDatabase::class.java,
            "gimy_tv.db"
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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
