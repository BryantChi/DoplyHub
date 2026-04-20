# 直播頻道（Live Channels）設計規格

- 日期：2026-04-21
- 目標版本：v1.1.0
- 狀態：**⚠️ 2026-04-21 擱置（SHELVED）**
- 實作方式：依現有 VOD 爬蟲架構擴充；以「公開官方 HLS 直播」為範圍

## ⚠️ 擱置原因（2026-04-21）

執行 `docs/superpowers/plans/2026-04-21-live-channels.md` Task 0（HLS probe）時發現：

- **台灣商業新聞台已全面遷移至 YouTube Live**：TVBS、三立、東森、民視、中天、華視、台視、中視、年代、非凡、壹電視、新唐人 — 官網無直接 m3u8，不是 JS 動態載入就是 iframe 嵌 YouTube。
- **本 spec 預設的 3 個 PoC 頻道全部失守**：公視 news.pts.org.tw/live 回 200 但 HTML 無 m3u8；華視 news.cts.com.tw/live 只有 `<iframe src="youtube.com/embed/...">`；民視 ftvnews.com.tw 被 Cloudflare WAF 擋 403。
- **台灣僅公共/宗教類有官方 HLS**：Taiwan Plus、原住民族電視台、大愛電視、好消息、人間衛視、CGNTV — 無新聞類。
- **替代路線評估**：
  - A1 範圍改公益文化（乾淨但不涵蓋新聞）
  - A2 YouTube iframe（WebView）— TV 遙控器體驗差
  - A3 A1+A2 混合
  - A4 NewPipeExtractor 整合 — 程式碼不複雜，但 GPL-3.0 感染整個 App、違反 YT ToS、每 3-6 個月需 bump library

使用者選擇暫時放棄。未來若重啟此功能，**請先從 replacement 路線重新 brainstorm**，不要照原 spec 繼續做。

Probe 詳細紀錄見本檔 commit 歷史與對應 plan。

## 1. 目標與範圍

### 1.1 要解決什麼
在現有 VOD 觀影體驗之外，提供 **公開官方直播頻道** 的即時收看能力，讓使用者在看劇之餘也能開啟新聞、體育、音樂、文化類直播。

### 1.2 Phase 1 明確範圍（YAGNI）
- **只收錄官方公開 HLS 串流**，不涉及 YouTube / Twitch 等需要逆向或違反 ToS 的平台。
- **4 個分類**：新聞、體育、音樂、文化。Phase 1 僅實作 **新聞** 分類的 3 個頻道作為 PoC：
  - 公視新聞（PTS News）
  - 民視新聞（FTV News）
  - 華視新聞（CTS News）
- 其他分類在架構上預留，但頻道清單為空，由後續階段擴充。

### 1.3 明確排除項目（Out of Scope）
- ❌ YouTube / Twitch 等需逆向的平台
- ❌ EPG 節目表
- ❌ 觀看紀錄（直播無進度）
- ❌ 納入全域搜尋（直播頁內自有分類切換）
- ❌ 使用者自帶 M3U 匯入（後續若有需要再議）
- ❌ 遠端 JSON 頻道管理（選擇 C 方案：爬蟲即時解析）

## 2. 架構總覽

採用與現有 `SiteSource` 平行的 `LiveSource` 抽象層，不混入 VOD 介面（避免 `fetchVodList`/`fetchVodDetail` 等不適用的方法污染直播語義）。

```
UI Layer
  ├── LiveScreen (Compose)          — 分類切換 + 頻道網格
  └── PlayerScreen (existing)       — isLive 旗標切換 UI

Domain Layer
  ├── LiveChannel / LiveCategory / LiveStreamInfo (models)
  └── LiveRepository (interface)

Data Layer
  ├── LiveRepositoryImpl            — 彙整多個 LiveSource、串流 URL 快取
  ├── LiveSource (interface)
  │     ├── PtsLiveSource           — 公視爬蟲
  │     ├── FtvLiveSource           — 民視爬蟲
  │     └── CtsLiveSource           — 華視爬蟲
  └── LiveStreamCacheDao            — HLS URL 10 分鐘快取（Room）
```

## 3. Domain Model

```kotlin
// 頻道本體（靜態資訊，不含 HLS URL）
data class LiveChannel(
    val id: String,              // 例: "pts-news"
    val name: String,            // "公視新聞"
    val categoryId: String,      // "news"
    val logoUrl: String,         // 台標/頻道 logo
    val description: String?,    // 一句話簡介（可選）
)

// 分類（UI 橫向切換用）
data class LiveCategory(
    val id: String,              // "news" / "sports" / "music" / "culture"
    val name: String,            // 「新聞」「體育」「音樂」「文化」
    val channels: List<LiveChannel>,
)

// 動態解析結果（從各 LiveSource 爬出的實際串流資訊）
data class LiveStreamInfo(
    val channelId: String,
    val hlsUrl: String,
    val headers: Map<String, String> = emptyMap(), // Referer / UA 等
    val resolvedAt: Long = System.currentTimeMillis(),
)
```

`SourceType` 維持不動（不新增 LIVE 值），避免現有 VOD 流程 `when (sourceType)` 的分支爆炸。直播走自己的獨立型別系統。

## 4. 資料層設計

### 4.1 LiveSource 介面

```kotlin
interface LiveSource {
    val channelId: String
    /** 解析頻道當下的可播放 HLS URL。失敗應拋例外，由 Repository 統一處理。 */
    suspend fun resolveStream(): LiveStreamInfo
}
```

每個頻道一個實作類別。頻道的靜態資訊（`LiveChannel` metadata）由 `LiveRepositoryImpl` 內建常數維護，這樣新增頻道時只要：

1. 新增一支 `XxxLiveSource` 實作
2. 在 `LiveRepositoryImpl` 的分類清單中註冊對應的 `LiveChannel` metadata
3. Hilt module 綁定

### 4.2 爬蟲實作要點（Phase 1）

各頻道官方直播頁的 HLS 取得邏輯不同，以下為調查結果：

| 頻道 | 官方直播頁 | HLS 取得方式 |
|------|-----------|--------------|
| 公視新聞 | `https://www.pts.org.tw/Content/prgIndex-news.html` | 頁面內嵌 `<video>` 或 JS 變數包含 m3u8，用 Jsoup 解析 |
| 民視新聞 | `https://www.ftvnews.com.tw/live/` 或官方 YouTube 嵌入 | 需確認是否有非 YouTube 的官方 HLS；若只有 YouTube 嵌入則此頻道降級為「暫不支援」 |
| 華視新聞 | `https://news.cts.com.tw/live/index.html` | 類似公視，頁面內有 m3u8 連結 |

⚠️ **實作前必做**：Phase 1 第一個任務是寫「HLS 可行性 probe 腳本」（可用 curl + grep 手動執行），實際確認三個頻道當下是否能取到可直接播放的 m3u8。若某頻道只提供 YouTube 嵌入，直接從 Phase 1 剔除並記錄於實作計畫。

### 4.3 串流 URL 快取

新增 Room Entity：

```kotlin
@Entity(tableName = "live_stream_cache")
data class LiveStreamCacheEntity(
    @PrimaryKey val channelId: String,
    val hlsUrl: String,
    val headersJson: String,    // Map<String, String> 序列化
    val resolvedAt: Long,
)
```

- TTL：10 分鐘（`System.currentTimeMillis() - resolvedAt < 10 * 60 * 1000`）
- Cache-hit：直接回傳快取的 `LiveStreamInfo`
- Cache-miss 或過期：呼叫對應 `LiveSource.resolveStream()`，成功後 upsert
- 播放失敗時（ExoPlayer 回報 `ERROR_CODE_IO_*`）：強制使快取失效並重新解析一次；若仍失敗則顯示錯誤 UI

### 4.4 Repository

```kotlin
interface LiveRepository {
    suspend fun getCategories(): List<LiveCategory>   // 靜態內建，立即回傳
    suspend fun getStreamInfo(channelId: String): LiveStreamInfo  // 走快取 + LiveSource
    suspend fun invalidateStream(channelId: String)   // 播放失敗時呼叫
}
```

### 4.5 資料庫 Migration

`GimyDatabase` 版本 `2 → 3`：新增 `live_stream_cache` 資料表。`FavoriteEntity` 需要擴充 `kind` 欄位以支援直播收藏（見 §5.3），同版本一併處理。

## 5. UI 層設計

### 5.1 導覽入口

`AdaptiveNavigation` 的 `navItems` 新增一項，位於「分類」與「收藏」之間：

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

`Screen` sealed class 新增：
```kotlin
data object Live : Screen("live")
data object LivePlayer : Screen("live_player/{channelId}") {
    fun createRoute(channelId: String) = "live_player/$channelId"
}
```

### 5.2 LiveScreen（直播頁）

版面（TV / 平板 / 手機 responsive）：

```
┌───────────────────────────────────────────┐
│  [新聞] [體育] [音樂] [文化]                │  ← 水平 tab，遙控器左右切
├───────────────────────────────────────────┤
│  ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐          │
│  │LOGO │ │LOGO │ │LOGO │ │LOGO │          │  ← 頻道卡片網格
│  │公視 │ │民視 │ │華視 │ │ ... │          │     columns: TV=5, tablet=4, phone=3
│  │新聞 │ │新聞 │ │新聞 │ │     │          │     focus scale 動畫沿用 VodCard
│  └─────┘ └─────┘ └─────┘ └─────┘          │
│  ┌─────┐ ┌─────┐                           │
│  │ ... │ │ ... │                           │
│  └─────┘ └─────┘                           │
└───────────────────────────────────────────┘
```

- 頻道卡片：顯示 logo + 頻道名，右上角「● LIVE」紅色徽章（用 `CinemaRed`）。
- 空分類（體育/音樂/文化 Phase 1 為空）顯示佔位文案：「更多頻道陸續加入中」，不顯示空白網格避免誤以為壞了。
- 點擊頻道 → 導覽到 `LivePlayer` route。

### 5.3 收藏整合

`FavoriteEntity` schema 擴充（migration v2 → v3）：

```kotlin
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String = "vod",        // ← 新增 "vod" / "live"
    val vodId: Long,                  // live 時存 channelId 的 hashCode，或改 TEXT 型別
    val sourceType: String,           // live 時存固定值 "LIVE"
    val title: String,
    val coverUrl: String,
    val category: String,             // live 時存 categoryId（"news" 等）
    val year: Int,                    // live 時存 0
    val status: String,               // live 時存 "LIVE"
    val addedAt: Long = System.currentTimeMillis(),
)
```

**取捨**：為最小化 migration 風險，複用現有欄位。`vodId` 對 VOD 仍是 Long，對 Live 用 `channelId.hashCode().toLong()`（確認 `LiveChannel.id` 為穩定 slug 字串，hashCode 碰撞機率極低可接受）。

`FavoriteRepository` 新增：
```kotlin
fun getLiveFavorites(): Flow<List<LiveChannel>>
suspend fun addLiveFavorite(channel: LiveChannel)
suspend fun removeLiveFavorite(channelId: String)
fun isLiveFavorite(channelId: String): Flow<Boolean>
```

`FavoritesScreen` 顯示時分兩段：「劇集/電影」與「直播頻道」，前者點擊走 `Detail`，後者點擊走 `LivePlayer`。

### 5.4 播放器改造

**不新增獨立 Live 播放器**，沿用 `PlayerScreen` / `PlayerViewModel`，以 `isLive` 旗標差異化：

| 行為 | VOD 模式 | Live 模式 |
|------|---------|-----------|
| 進度條 | 顯示可拖曳進度 | 改為 `● LIVE` 紅色徽章，不可拖曳 |
| 選集按鈕 | 顯示 | 隱藏 |
| 下一集 / 上一集 | 顯示 | 隱藏 |
| 長按倍速 | 保留 | 隱藏（直播倍速無意義） |
| 手勢亮度/音量 | 保留 | 保留 |
| 手勢左右滑動（前後 10 秒） | 保留 | 隱藏 |
| 跳回直播即時點按鈕 | — | 保留預設（ExoPlayer 本身支援） |
| 退出返回 | 回 Detail | 回 LiveScreen |

`Screen.LivePlayer` 路由帶 `channelId`，`PlayerViewModel` 依此向 `LiveRepository.getStreamInfo()` 取得 `hlsUrl`，丟給 ExoPlayer 播放。

### 5.5 錯誤與空狀態

| 情境 | UI 呈現 |
|------|---------|
| 無網路進入 LiveScreen | 頂部 banner「目前無網路連線，直播功能不可用」，網格仍顯示但點擊頻道跳錯誤提示 |
| 爬蟲解析失敗（HTTP 錯誤、DOM 變動） | Toast「此頻道暫時無法播放」並停留 LiveScreen |
| 播放中 HLS 中斷 | 全螢幕覆蓋層「直播連線中斷，正在嘗試重新連線…」，自動 invalidate 快取 + 重試一次；再失敗則顯示「無法連線，請稍後再試」 + 返回按鈕 |
| 某頻道爬蟲連續失敗 3 次 | 頻道卡片灰階化 + 右下角「⚠」小標（Phase 2 再加，Phase 1 先不做） |

## 6. 資料流（Sequence）

```
使用者點擊「公視新聞」卡片
  ↓
NavController.navigate("live_player/pts-news")
  ↓
PlayerViewModel(isLive=true, channelId="pts-news")
  ↓
LiveRepository.getStreamInfo("pts-news")
  ├─ 查 LiveStreamCacheDao → 未過期 → 回傳快取 LiveStreamInfo
  └─ 過期/無快取 → PtsLiveSource.resolveStream()
       ├─ OkHttp GET https://www.pts.org.tw/Content/prgIndex-news.html
       ├─ Jsoup parse → 萃取 m3u8
       └─ upsert 到 live_stream_cache
  ↓
ExoPlayer.setMediaSource(HlsMediaSource.Factory(...).createMediaSource(hlsUrl))
  ↓
播放。若 onPlayerError → LiveRepository.invalidateStream() + 重試一次
```

## 7. 測試策略

- **單元測試**：每個 `LiveSource` 實作以 fixture HTML（儲存於 `test/resources/live/`）驗證 HLS 萃取邏輯，確保 DOM 變動能快速被測試抓到。
- **快取測試**：`LiveRepositoryImpl` 驗證 TTL 過期、invalidate 流程。
- **整合測試**：手動（無 CI 環境跑真實 HTTP），在開發機跑一次 `./gradlew connectedAndroidTest` 驗證三個 LiveSource 能真的取到 m3u8，作為發版檢查項。
- **手動驗收**：在 Android TV 模擬器 + 實機手機各跑一次，驗證遙控器焦點、手勢、錯誤 UI。

## 8. 風險與緩解

| 風險 | 緩解 |
|------|------|
| 新聞台官網改版導致爬蟲壞掉 | 單元測試 + 每頻道獨立 LiveSource（壞一個不影響其他），並在 LiveScreen 對失敗頻道灰階化（Phase 2） |
| HLS 來源需要 Referer/User-Agent 才能播 | `LiveStreamInfo` 帶 `headers` 欄位，在建 `HlsMediaSource` 時透過 `DefaultHttpDataSource.Factory().setDefaultRequestProperties()` 塞入 |
| 某頻道只有 YouTube 嵌入 | Phase 1 實作前先 probe，不能用就從 Phase 1 剔除，不硬解 |
| 使用者網路慢導致 HLS 延遲高 | 使用 ExoPlayer 預設 LowLatency 設定，不另做處理（Phase 1 YAGNI） |
| 法律風險（重製新聞台內容） | 只播放官方公開串流 URL，不代管、不轉碼、不錄製；若收到 takedown 即下架該頻道 |

## 9. 迭代路徑

- **Phase 1（本 spec 範圍）**：3 個新聞頻道 PoC，驗證架構與爬蟲穩定性。預計 1-1.5 週。
- **Phase 2（未來）**：擴充體育（中華職棒官方直播）、音樂（KKBOX 直播?）、文化（公視表演廳）頻道；頻道失敗灰階化。
- **Phase 3（未來）**：評估是否切換到遠端 JSON 方案（方案 B），若爬蟲維運成本過高則升級。
- **Phase 4（未來）**：EPG 節目表、使用者 M3U 自帶清單。

## 10. 檔案影響清單（供實作計畫參考）

### 新增
- `app/src/main/java/com/gimy/tv/domain/model/Live.kt`（LiveChannel / LiveCategory / LiveStreamInfo）
- `app/src/main/java/com/gimy/tv/domain/repository/LiveRepository.kt`
- `app/src/main/java/com/gimy/tv/data/scraper/live/LiveSource.kt`
- `app/src/main/java/com/gimy/tv/data/scraper/live/PtsLiveSource.kt`
- `app/src/main/java/com/gimy/tv/data/scraper/live/FtvLiveSource.kt`（視 probe 結果）
- `app/src/main/java/com/gimy/tv/data/scraper/live/CtsLiveSource.kt`
- `app/src/main/java/com/gimy/tv/data/repository/LiveRepositoryImpl.kt`
- `app/src/main/java/com/gimy/tv/data/local/entity/LiveStreamCacheEntity.kt`
- `app/src/main/java/com/gimy/tv/data/local/dao/LiveStreamCacheDao.kt`
- `app/src/main/java/com/gimy/tv/ui/live/LiveScreen.kt`
- `app/src/main/java/com/gimy/tv/ui/live/LiveViewModel.kt`
- `app/src/main/java/com/gimy/tv/ui/live/LiveChannelCard.kt`

### 修改
- `app/src/main/java/com/gimy/tv/ui/navigation/Navigation.kt`（新增 Live / LivePlayer route）
- `app/src/main/java/com/gimy/tv/ui/components/AdaptiveNavigation.kt`（新增 nav item）
- `app/src/main/java/com/gimy/tv/ui/MainActivity.kt`（NavHost 掛新路由）
- `app/src/main/java/com/gimy/tv/ui/player/PlayerScreen.kt`（isLive 模式 UI 分支）
- `app/src/main/java/com/gimy/tv/ui/player/PlayerViewModel.kt`（isLive 分支 + LiveRepository 依賴）
- `app/src/main/java/com/gimy/tv/data/local/entity/Entities.kt`（FavoriteEntity 加 kind 欄位）
- `app/src/main/java/com/gimy/tv/data/local/dao/Daos.kt`（FavoriteDao 新增 live 查詢）
- `app/src/main/java/com/gimy/tv/data/repository/FavoriteRepositoryImpl.kt`
- `app/src/main/java/com/gimy/tv/domain/repository/Repositories.kt`（FavoriteRepository 擴充）
- `app/src/main/java/com/gimy/tv/data/local/GimyDatabase.kt`（version 2 → 3 + migration）
- `app/src/main/java/com/gimy/tv/ui/favorites/FavoritesScreen.kt`（分段顯示 VOD / Live）
- `app/src/main/java/com/gimy/tv/di/*.kt`（Hilt 模組綁 LiveSource / LiveRepository）

---

## Spec 自我檢視結果

- [x] Placeholder scan：無 TBD / TODO，唯一待驗證項（民視是否有非 YouTube 官方 HLS）已在 §4.2 明確標記為 Phase 1 第一個任務 probe
- [x] 內部一致性：分類有 4 類但 Phase 1 只做新聞 → 已於 §1.2 與 §5.2（空分類佔位 UI）呼應
- [x] 範圍：單一 implementation plan 可涵蓋，無需再拆
- [x] 歧義：isLive 模式 UI 差異於 §5.4 表格逐項列出；收藏欄位複用策略於 §5.3 明確說明
