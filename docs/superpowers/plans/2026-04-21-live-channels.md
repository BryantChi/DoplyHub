# 直播頻道 Implementation Plan

> **⚠️ 2026-04-21 擱置（SHELVED）** — Task 0 HLS probe 證實本 plan 前提不成立：
> - 公視 `news.pts.org.tw/live` HTML 無 m3u8（JS SPA）
> - 華視 `news.cts.com.tw/live` 只嵌 YouTube iframe
> - 民視 `ftvnews.com.tw` 被 Cloudflare WAF 擋 403
>
> 台灣商業新聞台已全面遷移 YouTube Live。未來若要重啟，請重新 brainstorm 選擇新路線，**不要照此 plan 繼續做**。詳見 spec 「擱置原因」章節。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增「直播」分頁，提供公開官方 HLS 直播頻道（Phase 1：公視/華視/民視新聞 3 台），沿用現有 ExoPlayer 以 isLive 模式播放，收藏整合至現有 Favorites。

**Architecture:** 與現有 `SiteSource` 平行的 `LiveSource` 抽象 + 各頻道獨立實作；`LiveRepository` 管理靜態頻道 metadata 與 HLS URL 的 10 分鐘 Room 快取；`PlayerScreen` 以 `isLive` 旗標差異化 UI。

**Tech Stack:** Kotlin, Jetpack Compose (TV / Material3), Media3 ExoPlayer + HLS, Jsoup + OkHttp, Room, Hilt, Coroutines/Flow

**Spec reference:** `docs/superpowers/specs/2026-04-21-live-channels-design.md`

---

## Task 0: 直播源可行性 Probe（不動程式碼）

**目的：** 動工前先確認三個目標新聞台是否真的有「非 YouTube 嵌入的官方公開 HLS」。失敗的頻道直接從 Phase 1 剔除，不要寫完爬蟲才發現沒東西可抓。

**Files:** 無（僅手動驗證）

- [ ] **Step 1: 對每個目標頻道執行 HLS probe**

對下列三個官方直播頁逐一執行：

```bash
# 公視新聞
curl -sL -A "Mozilla/5.0" "https://www.pts.org.tw/Content/prgIndex-news.html" \
  | grep -oE 'https?://[^"'"'"' ]+\.m3u8[^"'"'"' ]*' | head -5

# 華視新聞
curl -sL -A "Mozilla/5.0" "https://news.cts.com.tw/live/index.html" \
  | grep -oE 'https?://[^"'"'"' ]+\.m3u8[^"'"'"' ]*' | head -5

# 民視新聞（若無 m3u8 輸出則極可能只有 YouTube 嵌入）
curl -sL -A "Mozilla/5.0" "https://www.ftvnews.com.tw/live/" \
  | grep -oE 'https?://[^"'"'"' ]+\.m3u8[^"'"'"' ]*' | head -5
```

- [ ] **Step 2: 對每個取得的 m3u8 URL 驗證可播性**

```bash
# 將 URL 填入下列指令，確認 HTTP 200 且 Content-Type 為 application/vnd.apple.mpegurl 或 application/x-mpegURL
curl -sI -A "Mozilla/5.0" "<m3u8_url>"
```

- [ ] **Step 3: 紀錄 probe 結果**

在本檔案開頭新增一節「Probe Results (YYYY-MM-DD)」：

```markdown
## Probe Results (2026-04-21)
| 頻道 | HLS 可取得 | 需 Referer/UA | m3u8 URL 模式 | 結論 |
|------|-----------|--------------|-------------|------|
| 公視 | ✅ / ❌ | ... | ... | 列入 Phase 1 / 剔除 |
| 華視 | ... | ... | ... | ... |
| 民視 | ... | ... | ... | ... |
```

- [ ] **Step 4: Commit probe 結果**

```bash
git add docs/superpowers/plans/2026-04-21-live-channels.md
git commit -m "紀錄直播頻道 HLS probe 結果"
```

**Gate：** 若三個頻道全部無法取得 HLS，回到設計階段重新選擇目標頻道（改做體育類或國際新聞），不要進 Task 1。至少需要 1 個頻道可行才繼續。

---

## Task 1: Domain Models（LiveChannel / LiveCategory / LiveStreamInfo）

**Files:**
- Create: `app/src/main/java/com/gimy/tv/domain/model/Live.kt`

- [ ] **Step 1: 新增 Live domain models**

```kotlin
// app/src/main/java/com/gimy/tv/domain/model/Live.kt
package com.gimy.tv.domain.model

data class LiveChannel(
    val id: String,              // stable slug e.g. "pts-news"
    val name: String,            // 「公視新聞」
    val categoryId: String,      // "news" / "sports" / "music" / "culture"
    val logoUrl: String,
    val description: String? = null,
)

data class LiveCategory(
    val id: String,
    val name: String,
    val channels: List<LiveChannel>,
)

data class LiveStreamInfo(
    val channelId: String,
    val hlsUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val resolvedAt: Long = System.currentTimeMillis(),
)

object LiveCategoryIds {
    const val NEWS = "news"
    const val SPORTS = "sports"
    const val MUSIC = "music"
    const val CULTURE = "culture"
}
```

- [ ] **Step 2: Build 確認編譯通過**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/gimy/tv/domain/model/Live.kt
git commit -m "新增直播 domain models（LiveChannel/LiveCategory/LiveStreamInfo）"
```

---

## Task 2: Room 快取 Entity + DAO + Migration v2→v3

**Files:**
- Modify: `app/src/main/java/com/gimy/tv/data/local/entity/Entities.kt`
- Modify: `app/src/main/java/com/gimy/tv/data/local/dao/Daos.kt`
- Modify: `app/src/main/java/com/gimy/tv/data/local/GimyDatabase.kt`
- Modify: `app/src/main/java/com/gimy/tv/di/DatabaseModule.kt`

- [ ] **Step 1: 新增 LiveStreamCacheEntity**

在 `Entities.kt` 末尾新增：

```kotlin
@Entity(tableName = "live_stream_cache")
data class LiveStreamCacheEntity(
    @PrimaryKey val channelId: String,
    val hlsUrl: String,
    val headersJson: String,        // Map<String, String> 以 JSON 字串序列化
    val resolvedAt: Long,
)
```

- [ ] **Step 2: 新增 LiveStreamCacheDao**

在 `Daos.kt` 末尾新增：

```kotlin
@Dao
interface LiveStreamCacheDao {
    @Query("SELECT * FROM live_stream_cache WHERE channelId = :channelId")
    suspend fun get(channelId: String): LiveStreamCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LiveStreamCacheEntity)

    @Query("DELETE FROM live_stream_cache WHERE channelId = :channelId")
    suspend fun delete(channelId: String)
}
```

若 `Daos.kt` 頂部尚未 import `OnConflictStrategy`/`Insert`，補上 import。

- [ ] **Step 3: 在 GimyDatabase 註冊 entity 與 DAO，並寫 migration**

```kotlin
// app/src/main/java/com/gimy/tv/data/local/GimyDatabase.kt
package com.gimy.tv.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gimy.tv.data.local.dao.*
import com.gimy.tv.data.local.entity.*

@Database(
    entities = [
        FavoriteEntity::class,
        WatchHistoryEntity::class,
        VodCacheEntity::class,
        SearchHistoryEntity::class,
        MovieffmSlugEntity::class,
        LiveStreamCacheEntity::class,   // ← 新增
    ],
    version = 3,                         // ← 2 → 3
    exportSchema = false
)
abstract class GimyDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun vodCacheDao(): VodCacheDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun movieffmSlugDao(): MovieffmSlugDao
    abstract fun liveStreamCacheDao(): LiveStreamCacheDao  // ← 新增

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS live_stream_cache (
                        channelId TEXT NOT NULL PRIMARY KEY,
                        hlsUrl TEXT NOT NULL,
                        headersJson TEXT NOT NULL,
                        resolvedAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }
    }
}
```

- [ ] **Step 4: DatabaseModule 加上 migration 與新 DAO provider**

在 `DatabaseModule.kt` 中，`Room.databaseBuilder(...)` 鏈式呼叫加上 `.addMigrations(GimyDatabase.MIGRATION_2_3)`；並新增：

```kotlin
@Provides
fun provideLiveStreamCacheDao(db: GimyDatabase) = db.liveStreamCacheDao()
```

（若該檔案現用 `.fallbackToDestructiveMigration()`，改為 `.addMigrations(GimyDatabase.MIGRATION_2_3)` 保留使用者 data。）

- [ ] **Step 5: 編譯驗證**

```bash
./gradlew :app:compileDebugKotlin :app:kspDebugKotlin
```
Expected: BUILD SUCCESSFUL。若 ksp 報 Room schema 錯誤，檢查 entity import 與 version 是否一致。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/gimy/tv/data/local/
git add app/src/main/java/com/gimy/tv/di/DatabaseModule.kt
git commit -m "新增 live_stream_cache 資料表（DB v3 migration）"
```

---

## Task 3: LiveSource 介面

**Files:**
- Create: `app/src/main/java/com/gimy/tv/data/scraper/live/LiveSource.kt`

- [ ] **Step 1: 定義介面**

```kotlin
// app/src/main/java/com/gimy/tv/data/scraper/live/LiveSource.kt
package com.gimy.tv.data.scraper.live

import com.gimy.tv.domain.model.LiveStreamInfo

/**
 * Each live channel has its own implementation. Throws on failure;
 * LiveRepository decides retry / fallback policy.
 */
interface LiveSource {
    val channelId: String
    suspend fun resolveStream(): LiveStreamInfo
}

class LiveSourceException(message: String, cause: Throwable? = null) : Exception(message, cause)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/gimy/tv/data/scraper/live/LiveSource.kt
git commit -m "新增 LiveSource 介面"
```

---

## Task 4: 建立單元測試基礎設施 + PtsLiveSource 實作（TDD）

**Files:**
- Create: `app/src/test/java/com/gimy/tv/data/scraper/live/PtsLiveSourceTest.kt`
- Create: `app/src/test/resources/live/pts_sample.html`（從 probe 結果存一份真實 HTML 快照）
- Create: `app/src/main/java/com/gimy/tv/data/scraper/live/PtsLiveSource.kt`

- [ ] **Step 1: 抓一份 HTML fixture**

用 Task 0 probe 時的真實頁面做 fixture：

```bash
mkdir -p app/src/test/resources/live
curl -sL -A "Mozilla/5.0" "https://www.pts.org.tw/Content/prgIndex-news.html" \
  > app/src/test/resources/live/pts_sample.html
```

檢視 `pts_sample.html` 確認內含 m3u8 URL。若 URL 嵌在 JS 變數中（例：`var liveUrl = "https://.../live.m3u8";`），記下正規則模式。

- [ ] **Step 2: 寫失敗測試**

```kotlin
// app/src/test/java/com/gimy/tv/data/scraper/live/PtsLiveSourceTest.kt
package com.gimy.tv.data.scraper.live

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PtsLiveSourceTest {

    private fun loadFixture(name: String): String =
        javaClass.getResourceAsStream("/live/$name")!!
            .bufferedReader().use { it.readText() }

    @Test
    fun `extracts hls url from pts homepage`() = runTest {
        val html = loadFixture("pts_sample.html")
        val url = PtsLiveSource.extractHlsUrl(html)
        assertNotNull("Expected HLS URL to be extracted", url)
        assertTrue("URL should end with .m3u8", url!!.endsWith(".m3u8"))
        assertTrue("URL should be absolute", url.startsWith("http"))
    }

    @Test
    fun `returns null for html without m3u8`() = runTest {
        val html = "<html><body>no stream here</body></html>"
        val url = PtsLiveSource.extractHlsUrl(html)
        assertNull(url)
    }
}
```

- [ ] **Step 3: Run test to verify failure**

```bash
./gradlew :app:testDebugUnitTest --tests "com.gimy.tv.data.scraper.live.PtsLiveSourceTest"
```
Expected: FAIL（`PtsLiveSource` 不存在或 method 未定義）

- [ ] **Step 4: 實作 PtsLiveSource**

```kotlin
// app/src/main/java/com/gimy/tv/data/scraper/live/PtsLiveSource.kt
package com.gimy.tv.data.scraper.live

import com.gimy.tv.domain.model.LiveStreamInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

class PtsLiveSource @Inject constructor(
    private val client: OkHttpClient,
) : LiveSource {

    override val channelId = "pts-news"

    override suspend fun resolveStream(): LiveStreamInfo = withContext(Dispatchers.IO) {
        val html = fetchHtml(LIVE_PAGE)
        val url = extractHlsUrl(html)
            ?: throw LiveSourceException("PTS: no m3u8 found on $LIVE_PAGE")
        LiveStreamInfo(
            channelId = channelId,
            hlsUrl = url,
            headers = mapOf("Referer" to LIVE_PAGE, "User-Agent" to UA),
        )
    }

    private fun fetchHtml(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw LiveSourceException("PTS HTTP ${resp.code}")
            resp.body?.string() ?: throw LiveSourceException("PTS empty body")
        }
    }

    companion object {
        private const val LIVE_PAGE = "https://www.pts.org.tw/Content/prgIndex-news.html"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120"
        private val M3U8_REGEX = Regex("""https?://[^"' \\>]+\.m3u8[^"' \\<>]*""")

        fun extractHlsUrl(html: String): String? =
            M3U8_REGEX.find(html)?.value
    }
}
```

⚠️ 若 probe 時發現 PTS 的 m3u8 URL 是相對路徑或需經過 JS runtime 拼接，改用具體的 Jsoup / JSON 解析邏輯取代 regex。以 probe 結果為準，不要憑空發明解析方式。

- [ ] **Step 5: Run test to verify pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.gimy.tv.data.scraper.live.PtsLiveSourceTest"
```
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/gimy/tv/data/scraper/live/PtsLiveSource.kt
git add app/src/test/
git commit -m "實作 PtsLiveSource 並建立單元測試（含 HTML fixture）"
```

---

## Task 5: CtsLiveSource + FtvLiveSource（依 probe 結果）

**Files:**
- Create: `app/src/main/java/com/gimy/tv/data/scraper/live/CtsLiveSource.kt`
- Create: `app/src/test/java/com/gimy/tv/data/scraper/live/CtsLiveSourceTest.kt`
- Create: `app/src/test/resources/live/cts_sample.html`
- Create (conditional): `app/src/main/java/com/gimy/tv/data/scraper/live/FtvLiveSource.kt`（若 probe 確認民視有官方 HLS）

- [ ] **Step 1: 抓 CTS fixture**

```bash
curl -sL -A "Mozilla/5.0" "https://news.cts.com.tw/live/index.html" \
  > app/src/test/resources/live/cts_sample.html
```

- [ ] **Step 2: 寫 CtsLiveSourceTest（失敗狀態）**

結構同 `PtsLiveSourceTest`，fixture 換成 `cts_sample.html`，類別 `CtsLiveSource.extractHlsUrl()`。

- [ ] **Step 3: 驗證失敗 → 實作 CtsLiveSource → 驗證通過**

實作與 `PtsLiveSource` 結構相同，僅 `channelId = "cts-news"`、`LIVE_PAGE` 改為 `https://news.cts.com.tw/live/index.html`。

```bash
./gradlew :app:testDebugUnitTest --tests "com.gimy.tv.data.scraper.live.CtsLiveSourceTest"
```
Expected: PASS

- [ ] **Step 4: FtvLiveSource 條件分支**

依 Task 0 probe 結果：

- 若民視有官方 HLS：照 Pts/Cts 模式實作 `FtvLiveSource`（channelId = "ftv-news"）+ test + fixture
- 若民視僅有 YouTube 嵌入：**跳過本步驟**，並在 plan 檔 Probe Results 表格註明「Phase 1 剔除，原因：僅 YouTube 嵌入」

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/gimy/tv/data/scraper/live/
git add app/src/test/
git commit -m "實作 CtsLiveSource（與 FtvLiveSource 若可行）"
```

---

## Task 6: LiveRepository Interface + Impl（含 10 分鐘快取）

**Files:**
- Create: `app/src/main/java/com/gimy/tv/domain/repository/LiveRepository.kt`
- Create: `app/src/main/java/com/gimy/tv/data/repository/LiveRepositoryImpl.kt`

- [ ] **Step 1: Domain interface**

```kotlin
// app/src/main/java/com/gimy/tv/domain/repository/LiveRepository.kt
package com.gimy.tv.domain.repository

import com.gimy.tv.domain.model.LiveCategory
import com.gimy.tv.domain.model.LiveChannel
import com.gimy.tv.domain.model.LiveStreamInfo

interface LiveRepository {
    /** 靜態內建頻道分類。Phase 1 只有 news 有內容。 */
    fun getCategories(): List<LiveCategory>

    /** 查快取或觸發爬蟲解析。失敗拋例外。 */
    suspend fun getStreamInfo(channelId: String): LiveStreamInfo

    /** 播放失敗時讓快取失效，下次強制重新解析。 */
    suspend fun invalidateStream(channelId: String)

    fun findChannel(channelId: String): LiveChannel?
}
```

- [ ] **Step 2: Implementation with cache**

```kotlin
// app/src/main/java/com/gimy/tv/data/repository/LiveRepositoryImpl.kt
package com.gimy.tv.data.repository

import com.gimy.tv.data.local.dao.LiveStreamCacheDao
import com.gimy.tv.data.local.entity.LiveStreamCacheEntity
import com.gimy.tv.data.scraper.live.LiveSource
import com.gimy.tv.data.scraper.live.LiveSourceException
import com.gimy.tv.domain.model.LiveCategory
import com.gimy.tv.domain.model.LiveCategoryIds
import com.gimy.tv.domain.model.LiveChannel
import com.gimy.tv.domain.model.LiveStreamInfo
import com.gimy.tv.domain.repository.LiveRepository
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveRepositoryImpl @Inject constructor(
    private val cacheDao: LiveStreamCacheDao,
    sources: Set<@JvmSuppressWildcards LiveSource>,
) : LiveRepository {

    private val sourcesById: Map<String, LiveSource> = sources.associateBy { it.channelId }

    private val categories: List<LiveCategory> = buildCategories()

    override fun getCategories(): List<LiveCategory> = categories

    override fun findChannel(channelId: String): LiveChannel? =
        categories.asSequence().flatMap { it.channels.asSequence() }
            .firstOrNull { it.id == channelId }

    override suspend fun getStreamInfo(channelId: String): LiveStreamInfo {
        cacheDao.get(channelId)?.let { cached ->
            if (System.currentTimeMillis() - cached.resolvedAt < CACHE_TTL_MS) {
                return cached.toStreamInfo()
            }
        }
        val source = sourcesById[channelId]
            ?: throw LiveSourceException("No LiveSource registered for $channelId")
        val fresh = source.resolveStream()
        cacheDao.upsert(fresh.toEntity())
        return fresh
    }

    override suspend fun invalidateStream(channelId: String) {
        cacheDao.delete(channelId)
    }

    private fun buildCategories(): List<LiveCategory> {
        // Only channels with a registered LiveSource appear in the UI;
        // this keeps Phase 1 automatic — delete a source, channel disappears.
        val news = listOfNotNull(
            channelIfAvailable("pts-news", "公視新聞", LiveCategoryIds.NEWS,
                "https://www.pts.org.tw/favicon.ico"),
            channelIfAvailable("cts-news", "華視新聞", LiveCategoryIds.NEWS,
                "https://news.cts.com.tw/favicon.ico"),
            channelIfAvailable("ftv-news", "民視新聞", LiveCategoryIds.NEWS,
                "https://www.ftvnews.com.tw/favicon.ico"),
        )
        return listOf(
            LiveCategory(LiveCategoryIds.NEWS, "新聞", news),
            LiveCategory(LiveCategoryIds.SPORTS, "體育", emptyList()),
            LiveCategory(LiveCategoryIds.MUSIC, "音樂", emptyList()),
            LiveCategory(LiveCategoryIds.CULTURE, "文化", emptyList()),
        )
    }

    private fun channelIfAvailable(
        id: String, name: String, categoryId: String, logo: String
    ): LiveChannel? =
        if (sourcesById.containsKey(id))
            LiveChannel(id = id, name = name, categoryId = categoryId, logoUrl = logo)
        else null

    private fun LiveStreamCacheEntity.toStreamInfo(): LiveStreamInfo {
        val headers = runCatching {
            val json = JSONObject(headersJson)
            buildMap {
                json.keys().forEach { put(it, json.getString(it)) }
            }
        }.getOrDefault(emptyMap())
        return LiveStreamInfo(channelId, hlsUrl, headers, resolvedAt)
    }

    private fun LiveStreamInfo.toEntity(): LiveStreamCacheEntity =
        LiveStreamCacheEntity(
            channelId = channelId,
            hlsUrl = hlsUrl,
            headersJson = JSONObject(headers as Map<*, *>).toString(),
            resolvedAt = resolvedAt,
        )

    companion object {
        private const val CACHE_TTL_MS = 10L * 60L * 1000L
    }
}
```

- [ ] **Step 3: Build 驗證**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: 編譯失敗（LiveSource Set 尚未有 Hilt multibinding 綁定）。這是預期的，下一個 Task 補。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/gimy/tv/domain/repository/LiveRepository.kt
git add app/src/main/java/com/gimy/tv/data/repository/LiveRepositoryImpl.kt
git commit -m "實作 LiveRepository（含 10 分鐘 HLS URL 快取）"
```

---

## Task 7: Hilt DI 綁定（LiveSource multibinding + LiveRepository）

**Files:**
- Create: `app/src/main/java/com/gimy/tv/di/LiveModule.kt`
- Modify: `app/src/main/java/com/gimy/tv/di/RepositoryModule.kt`

- [ ] **Step 1: 新增 LiveModule 使用 `@IntoSet` multibinding**

```kotlin
// app/src/main/java/com/gimy/tv/di/LiveModule.kt
package com.gimy.tv.di

import com.gimy.tv.data.scraper.live.CtsLiveSource
import com.gimy.tv.data.scraper.live.LiveSource
import com.gimy.tv.data.scraper.live.PtsLiveSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class LiveModule {

    @Binds
    @IntoSet
    abstract fun bindPtsLiveSource(impl: PtsLiveSource): LiveSource

    @Binds
    @IntoSet
    abstract fun bindCtsLiveSource(impl: CtsLiveSource): LiveSource

    // 若 FtvLiveSource 存在再加：
    // @Binds @IntoSet abstract fun bindFtvLiveSource(impl: FtvLiveSource): LiveSource
}
```

- [ ] **Step 2: 在 RepositoryModule 綁 LiveRepository**

在 `RepositoryModule.kt` 末尾新增：

```kotlin
@Binds
abstract fun bindLiveRepository(impl: LiveRepositoryImpl): LiveRepository
```

補上必要的 import：
```kotlin
import com.gimy.tv.data.repository.LiveRepositoryImpl
import com.gimy.tv.domain.repository.LiveRepository
```

- [ ] **Step 3: Build 驗證**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/gimy/tv/di/
git commit -m "Hilt 綁定 LiveSource multibinding 與 LiveRepository"
```

---

## Task 8: Navigation Routes（Screen.Live / Screen.LivePlayer）+ 導覽 tab

**Files:**
- Modify: `app/src/main/java/com/gimy/tv/ui/navigation/Navigation.kt`
- Modify: `app/src/main/java/com/gimy/tv/ui/components/AdaptiveNavigation.kt`

- [ ] **Step 1: 新增 routes**

```kotlin
// app/src/main/java/com/gimy/tv/ui/navigation/Navigation.kt
package com.gimy.tv.ui.navigation

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Browse : Screen("browse/{sourceType}/{typeId}") {
        fun createRoute(sourceType: String, typeId: Int) = "browse/$sourceType/$typeId"
    }
    data object Search : Screen("search")
    data object Detail : Screen("detail/{sourceType}/{vodId}") {
        fun createRoute(sourceType: String, vodId: Long) = "detail/$sourceType/$vodId"
    }
    data object Player : Screen("player/{sourceType}/{vodId}/{sourceId}/{episodeNum}") {
        fun createRoute(sourceType: String, vodId: Long, sourceId: Int, episodeNum: Int) =
            "player/$sourceType/$vodId/$sourceId/$episodeNum"
    }
    data object Categories : Screen("categories")
    data object Favorites : Screen("favorites")
    data object History : Screen("history")

    // ↓↓↓ 新增 ↓↓↓
    data object Live : Screen("live")
    data object LivePlayer : Screen("live_player/{channelId}") {
        fun createRoute(channelId: String) = "live_player/$channelId"
    }
}
```

- [ ] **Step 2: AdaptiveNavigation 插入 Live tab（位於分類與收藏之間）**

修改 `app/src/main/java/com/gimy/tv/ui/components/AdaptiveNavigation.kt`：

在 `import androidx.compose.material.icons.filled.*` 區塊補上 `LiveTv`：
```kotlin
import androidx.compose.material.icons.filled.LiveTv
```

修改 `navItems`：
```kotlin
val navItems = listOf(
    NavItem(Screen.Home.route, "首頁", Icons.Default.Home),
    NavItem(Screen.Search.route, "搜尋", Icons.Default.Search),
    NavItem(Screen.Categories.route, "分類", Icons.Default.VideoLibrary),
    NavItem(Screen.Live.route, "直播", Icons.Default.LiveTv),   // ← 新增
    NavItem(Screen.Favorites.route, "收藏", Icons.Default.Favorite),
    NavItem(Screen.History.route, "紀錄", Icons.Default.History),
)
```

修改 `showNavBar` 白名單加入 `Screen.Live.route`：
```kotlin
val showNavBar = currentRoute in listOf(
    Screen.Home.route, Screen.Search.route, Screen.Categories.route,
    Screen.Live.route,                                              // ← 新增
    Screen.Favorites.route, Screen.History.route
) || currentRoute?.startsWith("browse/") == true
```

- [ ] **Step 3: Build 驗證**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/gimy/tv/ui/navigation/Navigation.kt
git add app/src/main/java/com/gimy/tv/ui/components/AdaptiveNavigation.kt
git commit -m "新增 Live / LivePlayer 路由與導覽 tab"
```

---

## Task 9: LiveViewModel + LiveScreen + LiveChannelCard

**Files:**
- Create: `app/src/main/java/com/gimy/tv/ui/live/LiveViewModel.kt`
- Create: `app/src/main/java/com/gimy/tv/ui/live/LiveScreen.kt`
- Create: `app/src/main/java/com/gimy/tv/ui/live/LiveChannelCard.kt`

- [ ] **Step 1: LiveViewModel**

```kotlin
// app/src/main/java/com/gimy/tv/ui/live/LiveViewModel.kt
package com.gimy.tv.ui.live

import androidx.lifecycle.ViewModel
import com.gimy.tv.domain.model.LiveCategory
import com.gimy.tv.domain.repository.LiveRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class LiveUiState(
    val categories: List<LiveCategory> = emptyList(),
    val selectedCategoryId: String = "",
)

@HiltViewModel
class LiveViewModel @Inject constructor(
    liveRepository: LiveRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LiveUiState())
    val uiState: StateFlow<LiveUiState> = _uiState.asStateFlow()

    init {
        val cats = liveRepository.getCategories()
        _uiState.value = LiveUiState(
            categories = cats,
            // Default to first category that has channels, fallback to first
            selectedCategoryId = cats.firstOrNull { it.channels.isNotEmpty() }?.id
                ?: cats.firstOrNull()?.id.orEmpty()
        )
    }

    fun selectCategory(categoryId: String) {
        _uiState.value = _uiState.value.copy(selectedCategoryId = categoryId)
    }
}
```

- [ ] **Step 2: LiveChannelCard**

```kotlin
// app/src/main/java/com/gimy/tv/ui/live/LiveChannelCard.kt
package com.gimy.tv.ui.live

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gimy.tv.domain.model.LiveChannel
import com.gimy.tv.ui.theme.*

@Composable
fun LiveChannelCard(
    channel: LiveChannel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "liveCardScale")

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(CinemaElevated)
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = CinemaRed,
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            AsyncImage(
                model = channel.logoUrl,
                contentDescription = channel.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black),
            )
            Text(
                text = channel.name,
                color = CinemaTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        // ● LIVE badge top-right
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(CinemaRed)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = "● LIVE",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
```

- [ ] **Step 3: LiveScreen**

```kotlin
// app/src/main/java/com/gimy/tv/ui/live/LiveScreen.kt
package com.gimy.tv.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gimy.tv.ui.theme.*

@Composable
fun LiveScreen(
    onChannelClick: (channelId: String) -> Unit,
    viewModel: LiveViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val selected = state.categories.firstOrNull { it.id == state.selectedCategoryId }
    val isTV = LocalIsTelevision.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CinemaBackground)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(
            text = "直播",
            color = CinemaTextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        // Category tabs
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
        ) {
            state.categories.forEach { cat ->
                val active = cat.id == state.selectedCategoryId
                Text(
                    text = cat.name,
                    color = if (active) Color.White else CinemaTextMuted,
                    fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (active) CinemaRed else CinemaElevated)
                        .clickable { viewModel.selectCategory(cat.id) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        // Channel grid or empty state
        if (selected == null || selected.channels.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "更多頻道陸續加入中",
                    color = CinemaTextMuted,
                    fontSize = 14.sp,
                )
            }
        } else {
            val columns = if (isTV) 5 else 3
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(selected.channels, key = { it.id }) { channel ->
                    LiveChannelCard(
                        channel = channel,
                        onClick = { onChannelClick(channel.id) },
                        modifier = Modifier.aspectRatio(1.2f),
                    )
                }
            }
        }
    }
}
```

⚠️ `clickable` import 需補上：`import androidx.compose.foundation.clickable`

- [ ] **Step 4: MainActivity NavHost 掛 Live route**

在 `MainActivity.kt` 的 `NavHost { ... }` block 內，新增 composable：

```kotlin
import com.gimy.tv.ui.live.LiveScreen
// ...

composable(Screen.Live.route) {
    LiveScreen(
        onChannelClick = { channelId ->
            navController.navigate(Screen.LivePlayer.createRoute(channelId))
        }
    )
}
```

- [ ] **Step 5: Build + 安裝到裝置做手動 smoke test**

```bash
./gradlew :app:installDebug
```

手動驗證：
1. 啟動 App，點導覽列「直播」
2. 看見「新聞 / 體育 / 音樂 / 文化」四個 tab
3. 新聞 tab 下應有 2-3 張頻道卡片（依 probe 結果）
4. 其他 tab 下顯示「更多頻道陸續加入中」
5. TV 模擬器測試遙控器 D-Pad 焦點能在頻道卡之間移動，獲得焦點時卡片放大 + 紅色邊框

⚠️ 點擊頻道會 navigate 到 `live_player/...` 但該路由尚未實作 → 預期崩潰或白畫面。這是 Task 10-11 要補的。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/gimy/tv/ui/live/
git add app/src/main/java/com/gimy/tv/ui/MainActivity.kt
git commit -m "新增 LiveScreen 與 LiveChannelCard，主畫面掛載直播分頁"
```

---

## Task 10: PlayerViewModel 支援 isLive 模式

**Files:**
- Modify: `app/src/main/java/com/gimy/tv/ui/player/PlayerViewModel.kt`

**設計原則：** 不重寫 `loadPlayer()`，而是在 init 根據 SavedStateHandle 決定走 VOD path 或 Live path。新增獨立方法 `loadLive()`，保持 VOD 邏輯不動。

- [ ] **Step 1: PlayerUiState 加旗標**

在 `PlayerUiState` data class 新增：

```kotlin
data class PlayerUiState(
    val isLoading: Boolean = true,
    val loadingMessage: String = "正在載入播放資訊…",
    val streamUrl: String? = null,
    val streamHeaders: Map<String, String> = emptyMap(),   // ← 新增
    val vodTitle: String = "",
    val episodeTitle: String = "",
    val episodeNum: Int = 1,
    val sourceId: Int = 1,
    val sourceName: String = "",
    val resumePositionMs: Long = 0L,
    val allSources: List<EpisodeGroup> = emptyList(),
    val totalEpisodes: Int = 0,
    val error: String? = null,
    val isFullscreen: Boolean = false,
    val isLive: Boolean = false,                            // ← 新增
    val liveChannelId: String? = null,                      // ← 新增
)
```

- [ ] **Step 2: Inject LiveRepository，新增 live 分流**

修改 class signature 與 init：

```kotlin
import com.gimy.tv.domain.model.LiveChannel
import com.gimy.tv.domain.model.LiveStreamInfo
import com.gimy.tv.domain.repository.LiveRepository

@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val vodRepository: VodRepository,
    private val watchHistoryRepository: WatchHistoryRepository,
    private val liveRepository: LiveRepository,             // ← 新增
) : ViewModel() {

    // 現有 VOD fields 保留
    private val sourceTypeName: String = savedStateHandle["sourceType"] ?: "GIMYMAX"
    val vodId: Long? = savedStateHandle.get<String>("vodId")?.toLongOrNull()
    private val initialSourceId: Int = savedStateHandle.get<String>("sourceId")?.toIntOrNull() ?: 0
    private val initialEpisodeNum: Int = savedStateHandle.get<String>("episodeNum")?.toIntOrNull() ?: 1
    val sourceType = runCatching { SourceType.valueOf(sourceTypeName) }.getOrDefault(SourceType.GIMYMAX)

    // ← 新增
    private val liveChannelId: String? = savedStateHandle.get<String>("channelId")

    private val _uiState = MutableStateFlow(PlayerUiState(
        episodeNum = initialEpisodeNum,
        sourceId = initialSourceId,
        isLive = liveChannelId != null,
        liveChannelId = liveChannelId,
    ))
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var vodDetail: VodDetail? = null

    init {
        if (liveChannelId != null) loadLive(liveChannelId) else loadPlayer()
    }
    // ...
}
```

- [ ] **Step 3: 新增 loadLive / retryLive 方法**

新增於 class 內（放在 `loadPlayer()` 之後、`saveProgress()` 之前）：

```kotlin
private fun loadLive(channelId: String) {
    viewModelScope.launch {
        _uiState.update { it.copy(isLoading = true, error = null, loadingMessage = "正在載入直播…") }
        val channel = liveRepository.findChannel(channelId)
        if (channel == null) {
            _uiState.update { it.copy(isLoading = false, error = "找不到直播頻道") }
            return@launch
        }
        _uiState.update { it.copy(vodTitle = channel.name, episodeTitle = "LIVE") }
        try {
            val info = liveRepository.getStreamInfo(channelId)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    streamUrl = info.hlsUrl,
                    streamHeaders = info.headers,
                )
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, error = "直播載入失敗: ${e.message}") }
        }
    }
}

/** 播放失敗時呼叫：清快取、重新解析一次。 */
fun retryLive() {
    val id = liveChannelId ?: return
    viewModelScope.launch {
        runCatching { liveRepository.invalidateStream(id) }
        loadLive(id)
    }
}
```

- [ ] **Step 4: 在 VOD 專屬的 saveProgress / switchEpisode / switchSource / nextEpisode / retryWithNextSource / enrichWithCrossSource 開頭加 early return 防護**

以 `saveProgress` 為例：

```kotlin
fun saveProgress(positionMs: Long, durationMs: Long) {
    if (_uiState.value.isLive) return   // ← live 不記錄進度
    val id = vodId ?: return
    // ... 原邏輯不動
}

fun switchEpisode(episodeNum: Int) {
    if (_uiState.value.isLive) return
    // ... 原邏輯不動
}

fun switchSource(sourceId: Int) {
    if (_uiState.value.isLive) return
    // ... 原邏輯不動
}

fun nextEpisode() {
    if (_uiState.value.isLive) return
    switchEpisode(_uiState.value.episodeNum + 1)
}

fun retryWithNextSource() {
    if (_uiState.value.isLive) { retryLive(); return }
    // ... 原邏輯不動
}

private fun enrichWithCrossSource() {
    if (_uiState.value.isLive) return
    // ... 原邏輯不動
}
```

- [ ] **Step 5: Build 驗證**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/gimy/tv/ui/player/PlayerViewModel.kt
git commit -m "PlayerViewModel 支援 isLive 模式（新增 loadLive/retryLive）"
```

---

## Task 11: PlayerScreen UI 差異化 + MainActivity 掛 LivePlayer route

**Files:**
- Modify: `app/src/main/java/com/gimy/tv/ui/player/PlayerScreen.kt`
- Modify: `app/src/main/java/com/gimy/tv/ui/MainActivity.kt`

- [ ] **Step 1: PlayerScreen 在 uiState.isLive 時的 UI 調整**

在 `PlayerScreen.kt` 中找到以下位置分別加 isLive 分支：

**a) Controller 顯示策略**：讓 PlayerView 使用 `useController = !uiState.isLive`（直播隱藏預設 seek controller），或保留 controller 但在 isLive 時隱藏進度條。具體依現有實作選擇侵入最小的做法。

**b) 進度條替換為 LIVE 徽章：**

```kotlin
if (uiState.isLive) {
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(16.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(CinemaRed)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = "● LIVE",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
```

**c) 隱藏 VOD 專屬按鈕：** 在顯示「下一集 / 選集 / 線路切換」的區塊外層加 `if (!uiState.isLive) { ... }` 條件。

**d) 手勢處理：** 在 `PlayerGestureOverlay` 呼叫點加入旗標，使其在 isLive 時停用「左右滑動 seek」（亮度/音量保留）。若現有元件尚未提供此旗標，這一步同時在 `PlayerGestureOverlay.kt` 新增 `allowSeek: Boolean = true` 參數並在 composable 內包住 seek 相關 `detectHorizontalDragGestures`：

```kotlin
@Composable
fun PlayerGestureOverlay(
    // ... existing params
    allowSeek: Boolean = true,
) {
    // ... existing body
    // wrap seek drag detector:
    if (allowSeek) {
        // existing horizontal drag gesture block
    }
}
```

在 `PlayerScreen.kt` 呼叫時傳 `allowSeek = !uiState.isLive`。

**e) 設定 ExoPlayer MediaItem 時套用 headers：** 找到現有建立 `MediaSource.Factory` 的位置，改為：

```kotlin
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource

val httpFactory = DefaultHttpDataSource.Factory().apply {
    if (uiState.streamHeaders.isNotEmpty()) {
        setDefaultRequestProperties(uiState.streamHeaders)
    }
}
val mediaSource = HlsMediaSource.Factory(httpFactory)
    .createMediaSource(MediaItem.fromUri(uiState.streamUrl!!))
exoPlayer.setMediaSource(mediaSource)
exoPlayer.prepare()
```

⚠️ 若現有程式碼使用 `exoPlayer.setMediaItem(MediaItem.fromUri(url))` 簡化呼叫（不帶 MediaSource），改用上面 `HlsMediaSource` 版本才能套 headers。保留 fallback：若 `streamHeaders.isEmpty()`，行為與原本一致。

**f) Player `onPlayerError` 對 live 多一層 retry：** 在 ExoPlayer `Player.Listener` 內：

```kotlin
override fun onPlayerError(error: PlaybackException) {
    if (uiState.isLive && errorRetryCount < 1) {
        errorRetryCount++
        viewModel.retryLive()
        return
    }
    // existing VOD retry behaviour
}
```

- [ ] **Step 2: MainActivity 掛 LivePlayer route**

在 `MainActivity.kt` NavHost 內新增：

```kotlin
composable(
    Screen.LivePlayer.route,
    arguments = listOf(
        navArgument("channelId") { type = NavType.StringType },
    )
) {
    PlayerScreen(
        onBack = { navController.popBackStack() }
    )
}
```

⚠️ `PlayerScreen` 依賴 `PlayerViewModel`（hiltViewModel），`PlayerViewModel` 從 SavedStateHandle 讀 `channelId` 即可自動切到 live 分支，無需改動 `PlayerScreen` signature。

- [ ] **Step 3: Build + 安裝 + 手動驗收**

```bash
./gradlew :app:installDebug
```

驗收：
1. 進入直播 → 點公視新聞
2. 畫面切到播放器，看見 `● LIVE` 紅色徽章
3. 無「下一集 / 選集」按鈕
4. 上下滑動可調整音量/亮度，左右滑動無 seek 反應
5. 播放成功（HLS 流畫面出現）
6. 返回鍵正確回到 LiveScreen

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/gimy/tv/ui/player/
git add app/src/main/java/com/gimy/tv/ui/MainActivity.kt
git commit -m "PlayerScreen 支援直播 UI 與 headers，掛載 LivePlayer 路由"
```

---

## Task 12: Favorite schema migration 與直播收藏

**Files:**
- Modify: `app/src/main/java/com/gimy/tv/data/local/entity/Entities.kt`
- Modify: `app/src/main/java/com/gimy/tv/data/local/dao/Daos.kt`
- Modify: `app/src/main/java/com/gimy/tv/data/local/GimyDatabase.kt`
- Modify: `app/src/main/java/com/gimy/tv/domain/repository/Repositories.kt`
- Modify: `app/src/main/java/com/gimy/tv/data/repository/FavoriteRepositoryImpl.kt`

**決策：** DB 版本從 v3 再推進到 v4，新增 `kind` 欄位到 `favorites`，並保留 v2→v3 migration 共存。

- [ ] **Step 1: FavoriteEntity 加 kind 欄位**

```kotlin
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String = KIND_VOD,                          // ← 新增
    val vodId: Long,
    val sourceType: String,
    val title: String,
    val coverUrl: String,
    val category: String,
    val year: Int,
    val status: String,
    val addedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val KIND_VOD = "vod"
        const val KIND_LIVE = "live"
        const val LIVE_SOURCE_TYPE = "LIVE"
    }
}
```

- [ ] **Step 2: FavoriteDao 新增 live 查詢**

在現有 `FavoriteDao` interface 新增：

```kotlin
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    // ... existing queries

    @Query("SELECT * FROM favorites WHERE kind = 'vod' ORDER BY addedAt DESC")
    fun getAllVod(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE kind = 'live' ORDER BY addedAt DESC")
    fun getAllLive(): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE kind = 'live' AND vodId = :channelIdHash)")
    fun isLiveFavorite(channelIdHash: Long): Flow<Boolean>

    @Query("DELETE FROM favorites WHERE kind = 'live' AND vodId = :channelIdHash")
    suspend fun deleteLive(channelIdHash: Long)
}
```

**⚠️ 重要：** 現有 `getAll()` 查詢若未限制 `kind`，會把 live 收藏也撈進 VOD 列表 → `toVod()` 轉換會當掉（sourceType 不是合法 SourceType）。修改現有 `getAll` 查詢加條件：

```kotlin
@Query("SELECT * FROM favorites WHERE kind = 'vod' ORDER BY addedAt DESC")
fun getAll(): Flow<List<FavoriteEntity>>
```

- [ ] **Step 3: DB version 3 → 4 + migration**

在 `GimyDatabase.kt` companion object：

```kotlin
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE favorites ADD COLUMN kind TEXT NOT NULL DEFAULT 'vod'")
    }
}
```

更新 `@Database(version = 4, ...)`，DatabaseModule 加 `.addMigrations(GimyDatabase.MIGRATION_2_3, GimyDatabase.MIGRATION_3_4)`。

- [ ] **Step 4: FavoriteRepository 介面擴充**

在 `domain/repository/Repositories.kt`：

```kotlin
import com.gimy.tv.domain.model.LiveChannel

interface FavoriteRepository {
    fun getFavorites(): Flow<List<Vod>>
    fun isFavorite(vodId: Long, sourceType: SourceType): Flow<Boolean>
    suspend fun addFavorite(vod: Vod)
    suspend fun removeFavorite(vodId: Long, sourceType: SourceType)

    // ← 新增
    fun getLiveFavorites(): Flow<List<LiveChannel>>
    fun isLiveFavorite(channelId: String): Flow<Boolean>
    suspend fun addLiveFavorite(channel: LiveChannel)
    suspend fun removeLiveFavorite(channelId: String)
}
```

- [ ] **Step 5: FavoriteRepositoryImpl 實作**

在 `FavoriteRepositoryImpl.kt` 新增方法：

```kotlin
override fun getLiveFavorites(): Flow<List<LiveChannel>> =
    dao.getAllLive().map { entities ->
        entities.map { it.toLiveChannel() }
    }

override fun isLiveFavorite(channelId: String): Flow<Boolean> =
    dao.isLiveFavorite(channelId.hashCode().toLong())

override suspend fun addLiveFavorite(channel: LiveChannel) {
    dao.insert(
        FavoriteEntity(
            kind = FavoriteEntity.KIND_LIVE,
            vodId = channel.id.hashCode().toLong(),
            sourceType = FavoriteEntity.LIVE_SOURCE_TYPE,
            title = channel.name,
            coverUrl = channel.logoUrl,
            category = channel.categoryId,
            year = 0,
            status = "LIVE",
        )
    )
}

override suspend fun removeLiveFavorite(channelId: String) {
    dao.deleteLive(channelId.hashCode().toLong())
}

private fun FavoriteEntity.toLiveChannel() = LiveChannel(
    id = title.let { _ ->
        // Reverse lookup: we stored hashCode; fetch real channel from category metadata instead
        // We don't store channelId directly in vodId (it's a hash), so we reconstruct via categoryId + title match.
        // For Phase 1 the simplest path: re-derive from category display — acceptable because
        // getLiveFavorites is only used for rendering chips; clicking uses title-to-channelId lookup.
        deriveChannelIdFromTitle(title)
    },
    name = title,
    categoryId = category,
    logoUrl = coverUrl,
)

/** Phase 1 only 3 known channels. */
private fun deriveChannelIdFromTitle(title: String): String = when (title) {
    "公視新聞" -> "pts-news"
    "華視新聞" -> "cts-news"
    "民視新聞" -> "ftv-news"
    else -> ""
}
```

**⚠️ 架構 smell 說明：** 用 hashCode + 名稱反查 channelId 是 Phase 1 的妥協。Phase 2 應改為在 `FavoriteEntity` 新增獨立 `liveChannelId: String?` 欄位（TEXT，nullable），避免這種反推。寫進 Task 12 commit message 提醒自己。

- [ ] **Step 6: Build + 驗證**

```bash
./gradlew :app:assembleDebug
```
Expected: SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/gimy/tv/data/local/
git add app/src/main/java/com/gimy/tv/domain/repository/Repositories.kt
git add app/src/main/java/com/gimy/tv/data/repository/FavoriteRepositoryImpl.kt
git commit -m "Favorites 支援直播頻道（DB v4 kind 欄位，Phase 2 再獨立 liveChannelId）"
```

---

## Task 13: FavoritesScreen 分段顯示 + 頻道卡片加入收藏互動

**Files:**
- Modify: `app/src/main/java/com/gimy/tv/ui/favorites/FavoritesScreen.kt`
- Modify: `app/src/main/java/com/gimy/tv/ui/live/LiveScreen.kt`（可選：直播頁長按/選單收藏）
- Modify: `app/src/main/java/com/gimy/tv/ui/player/PlayerScreen.kt`（直播播放器加收藏按鈕）

**Phase 1 最小範圍：** 只在直播播放器加「收藏 / 取消收藏」按鈕；FavoritesScreen 把直播列在 VOD 收藏下方作為獨立區塊。不要在 LiveScreen 做長按選單（YAGNI）。

- [ ] **Step 1: FavoritesScreen 新增 Live section**

修改 `FavoritesScreen.kt`，在現有 VOD 收藏 LazyGrid 下方追加：

```kotlin
val liveFavorites by viewModel.liveFavorites.collectAsState(initial = emptyList())

if (liveFavorites.isNotEmpty()) {
    Text(
        text = "直播頻道",
        color = CinemaTextPrimary,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 24.dp, bottom = 12.dp),
    )
    LazyVerticalGrid(
        columns = GridCells.Fixed(if (isTV) 5 else 3),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(liveFavorites, key = { it.id }) { channel ->
            LiveChannelCard(
                channel = channel,
                onClick = { onLiveChannelClick(channel.id) },
                modifier = Modifier.aspectRatio(1.2f),
            )
        }
    }
}
```

`FavoritesScreen` signature 加入 `onLiveChannelClick: (String) -> Unit` 參數。MainActivity 綁定時導到 `Screen.LivePlayer.createRoute(channelId)`。

FavoritesViewModel 須新增：
```kotlin
val liveFavorites: Flow<List<LiveChannel>> = favoriteRepository.getLiveFavorites()
```

- [ ] **Step 2: PlayerScreen 直播模式新增收藏按鈕**

在 `PlayerScreen.kt` 的直播徽章附近（或 overlay 右上角）條件渲染一個 IconButton：

```kotlin
if (uiState.isLive && uiState.liveChannelId != null) {
    val isFav by viewModel.isCurrentLiveFavorite.collectAsState(initial = false)
    IconButton(
        onClick = { viewModel.toggleLiveFavorite() },
        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
    ) {
        Icon(
            imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            contentDescription = if (isFav) "取消收藏" else "加入收藏",
            tint = if (isFav) CinemaRed else Color.White,
        )
    }
}
```

PlayerViewModel 新增：

```kotlin
val isCurrentLiveFavorite: StateFlow<Boolean> = (liveChannelId?.let { favoriteRepository.isLiveFavorite(it) } ?: flowOf(false))
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

fun toggleLiveFavorite() {
    val id = liveChannelId ?: return
    val channel = liveRepository.findChannel(id) ?: return
    viewModelScope.launch {
        if (isCurrentLiveFavorite.value) {
            favoriteRepository.removeLiveFavorite(id)
        } else {
            favoriteRepository.addLiveFavorite(channel)
        }
    }
}
```

⚠️ PlayerViewModel constructor 要 inject `FavoriteRepository`（現有應該已用於 VOD；若無，加上 Hilt 注入）。

- [ ] **Step 3: Build + 手動驗證**

```bash
./gradlew :app:installDebug
```

驗證：
1. 進公視新聞 → 按收藏 → 看到愛心變紅
2. 退出到 Favorites → 下方「直播頻道」區塊顯示公視新聞卡
3. 點卡片 → 正確播放
4. 再進公視新聞 → 按取消收藏 → Favorites 區塊消失

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/gimy/tv/ui/favorites/
git add app/src/main/java/com/gimy/tv/ui/player/PlayerScreen.kt
git add app/src/main/java/com/gimy/tv/ui/player/PlayerViewModel.kt
git commit -m "FavoritesScreen 與 PlayerScreen 支援直播頻道收藏"
```

---

## Task 14: 端到端手動驗收 + Release notes

**Files:**
- Modify: `app/build.gradle.kts`（versionName）

**不做自動化 E2E（專案無此基礎設施），改為嚴謹的手動驗收 checklist。**

- [ ] **Step 1: 在 TV 模擬器執行完整驗收**

執行 Android TV 模擬器，逐項驗證：

- [ ] Home → 導覽列看到「直播」項目在「分類」與「收藏」之間
- [ ] 點「直播」進入，4 個分類 tab（新聞/體育/音樂/文化）全顯示
- [ ] 新聞 tab 顯示實際可用頻道（依 Task 0 probe 結果）
- [ ] 體育/音樂/文化 tab 顯示「更多頻道陸續加入中」佔位文案
- [ ] 遙控器 D-Pad 能在分類 tab 間切換（左右）
- [ ] 遙控器 D-Pad 能在頻道卡片網格移動焦點，焦點卡片放大 + 紅邊框
- [ ] 點頻道後進播放器，看見「● LIVE」紅色徽章
- [ ] 畫面實際出現 HLS 播放內容（非黑畫面）
- [ ] 播放器無「下一集/選集/線路切換」按鈕
- [ ] 按返回鍵回到 LiveScreen
- [ ] 按收藏 icon → 紅色愛心亮起；退出至 Favorites 下方區塊顯示該頻道
- [ ] 在 Favorites 點直播頻道卡 → 正確回到播放器
- [ ] 關閉 App 10 分鐘內重開直播，快取生效（不重新解析，開啟快）
- [ ] 殺掉 App 11 分鐘後重開，應觸發新的爬蟲解析（看 logcat 有網路請求）

- [ ] **Step 2: 在手機模擬器/實機驗收 responsive layout**

- [ ] 手機豎屏：頻道卡 3 欄
- [ ] 平板：頻道卡 4 欄
- [ ] TV：頻道卡 5 欄
- [ ] 手機底部 NavigationBar 顯示「直播」icon + 文字
- [ ] 平板/折疊裝置 NavigationRail 顯示「直播」

- [ ] **Step 3: 故障情境驗收**

- [ ] 開飛航模式進 LiveScreen → 頻道網格仍顯示，點頻道 → 顯示「直播載入失敗」錯誤 UI
- [ ] 關飛航模式 → 點頻道 → 成功播放
- [ ] 在 logcat filter 「PtsLive」等關鍵字確認爬蟲有跑
- [ ] 播放中斷開網路 → 看見重試邏輯；恢復網路後應可手動重試

- [ ] **Step 4: 版本升級**

修改 `app/build.gradle.kts`：

```kotlin
versionCode = 2
versionName = "1.1.0"
```

- [ ] **Step 5: Release notes 加到 CHANGELOG / commit message**

```bash
git add app/build.gradle.kts
git commit -m "發版 v1.1.0：新增直播頻道（Phase 1：新聞類）

- 新增導覽列「直播」分頁，支援新聞/體育/音樂/文化 4 大分類
- Phase 1 內建 3 個新聞台：公視新聞、華視新聞、民視新聞（依 probe 可行性）
- 沿用 ExoPlayer 播放 HLS 直播流，UI 以 ● LIVE 徽章標示
- 直播頻道可加入收藏，與 VOD 收藏分區顯示
- HLS URL 10 分鐘 Room 快取，播放失敗自動重新解析重試一次
- DB 升級 v2 → v4（新增 live_stream_cache 表；favorites 加 kind 欄位）"
```

---

## 整體完成 Checklist

- [ ] 所有 Task 的 commit 都已推入 dev branch
- [ ] `./gradlew :app:assembleRelease` 能產 release APK
- [ ] 在至少一台實機（TV 或手機）完整跑過 Task 14 驗收
- [ ] 已 update `docs/superpowers/specs/2026-04-21-live-channels-design.md` 中 §2.1.3 若有發現新排除項
- [ ] Phase 2 todo 已記錄（在 spec §9）：liveChannelId 獨立欄位、頻道失敗灰階化、擴充體育/音樂/文化頻道

---

## 附錄：常見狀況排除

| 狀況 | 可能原因 | 解法 |
|------|---------|------|
| 爬蟲測試過、APK 播不出來 | 缺 Referer/UA header | 檢查 `streamHeaders` 是否真的經 `DefaultHttpDataSource.Factory` 套上 |
| Room migration 崩潰 | `MIGRATION_2_3` 或 `MIGRATION_3_4` 漏加 | 清 App data 後再試；確認 `DatabaseModule.addMigrations(...)` 兩個都列 |
| Hilt multibinding 為空 | `@IntoSet` 漏註解或模組沒 install | 檢查 `LiveModule` 與 `@InstallIn(SingletonComponent::class)` |
| LiveScreen 頻道為空但 probe 測試 OK | `LiveRepositoryImpl.buildCategories()` 內 `channelIfAvailable` 檢查走 `sourcesById`，代表對應 LiveSource 沒被 Hilt 綁上 | 確認 `LiveModule` 有 `@Binds @IntoSet` 對應每個已實作的 Source |
| ExoPlayer 卡在 buffering | HLS live edge 太近 | 不處理（Phase 1 YAGNI），若明顯問題再調 `LiveConfiguration.Builder` |
