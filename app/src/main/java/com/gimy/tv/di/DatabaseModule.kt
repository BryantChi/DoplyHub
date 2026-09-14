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

    /**
     * v3.1.0 — persist the slug↔id map for jable / xnxx.
     *
     * Their ids are one-way hashes of the detail slug and the reverse map lived only in
     * memory, so favourites and history opened after a restart failed with "slug not in
     * cache". Composite key because both sources share the table.
     */
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `embed_slugs` (
                    `vodId` INTEGER NOT NULL,
                    `slug` TEXT NOT NULL,
                    `sourceType` TEXT NOT NULL,
                    `cachedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`sourceType`, `vodId`)
                )
            """.trimIndent())
        }
    }

    /**
     * v3.1.0 — track consecutive failed opens per saved entry.
     *
     * Lets favourites/history show an entry as stale, and lets the app retire one only after
     * repeated misses on a source that is itself provably healthy. Existing rows default to
     * 0 so nothing is considered stale until it actually fails.
     */
    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `favorites` ADD COLUMN `missCount` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `watch_history` ADD COLUMN `missCount` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * vod_cache 從 v1 就在，但整張表從來沒有被讀寫過——快取實際上都在記憶體裡
     * （VodRepositoryImpl 的 gimyHomeCache／detailCache）。空表不佔空間，但留著會讓人
     * 以為有一層磁碟快取在運作，查效能問題時找錯方向。
     */
    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS `vod_cache`")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): GimyDatabase {
        return Room.databaseBuilder(
            context,
            GimyDatabase::class.java,
            "gimy_tv.db"
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
         // 只在「降版」時才允許砍表重建。
         //
         // 原本是無條件的 fallbackToDestructiveMigration()：目前 1→6 的 migration 是連續的，
         // 所以升級路徑上不會觸發，但它涵蓋的是「找不到對應 migration」這整類情況——
         // 將來哪次漏寫一條，使用者的收藏與觀看紀錄就會被默默清掉，而且完全沒有提示。
         // 改成只保留降版那條路之後，漏寫 migration 會直接 crash，在自己機器上就會發現。
         .fallbackToDestructiveMigrationOnDowngrade()
         .build()
    }

    @Provides
    fun provideFavoriteDao(db: GimyDatabase): FavoriteDao = db.favoriteDao()

    @Provides
    fun provideWatchHistoryDao(db: GimyDatabase): WatchHistoryDao = db.watchHistoryDao()

    @Provides
    fun provideSearchHistoryDao(db: GimyDatabase): SearchHistoryDao = db.searchHistoryDao()

    @Provides
    fun provideMovieffmSlugDao(db: GimyDatabase): MovieffmSlugDao = db.movieffmSlugDao()

    @Provides
    @Singleton
    fun provideEmbedSlugDao(db: GimyDatabase): EmbedSlugDao = db.embedSlugDao()
}
