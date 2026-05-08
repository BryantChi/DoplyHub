# Multi-Source Aggregation + Adult Content Section Design

**Date**: 2026-05-08
**Status**: Draft
**Target version**: v2.1.0

---

## Context

App 目前已有 3 個內容來源：

| 來源 | 角色 |
|---|---|
| GimyTv (gimytv.ai) | 主來源（v2.0.4 起） |
| GimyMax (gimymax.com) | 備援 |
| Movieffm (movieffm.net) | 補充電影 |

需要擴增來源覆蓋面，提升內容廣度與單站故障時的可用性，並在獲得新內容池的同時保留家庭觀賞情境（隱藏成人內容）。

### Probe 結果（2026-05-08）

對 ddnews.com.tw/8637 推薦的 10 站做交叉比對，篩選出 5 個值得整合的站點：

| 來源 | URL 格式 | 內容定位 |
|---|---|---|
| **gimy.tw** 劇迷 | `/voddetail/{id}.html` | 標準 MacCMS，與 GimyTv 結構同 |
| **eynytv.com** 伊莉影音 | `/voddetail/{id}.html` | 全分類 |
| **imaple.tv** 楓林網 | `/voddetail/{id}.html` | 韓劇/動漫主力，名氣最大 |
| **momovod.app** | `/vod/{id}.html` | **陸劇深度差異化** |
| **123kubo.net** | `/vod/{id}.html` | **陸劇/韓劇新作差異化** |

排除：

| 站 | 原因 |
|---|---|
| 94itv.net | 301 跳轉至 94580.net，後者 HTTP 403（地區封鎖或反爬） |
| tv99kubo.tv / ztv.tw | 兩站採集源相同，且與 imaple 內容池高度重疊 |
| fh6666.net | 內容量僅數百部 |
| 329tv.app | URL pattern `/vod-detail-id-{id}.html` 為罕見 dash-rewrite，需獨立 URL 規則，維護成本高 |

### Probe 結論：5 站結構共通

所有 5 站都是 MacCMS 變體，但與現有 GimyMax/GimyTv 的 `player_data` JSON 結構**不同**：

```
詳情頁 HTML 結構：
  ├─ <h1> 標題
  ├─ 元資訊（年份/分類/演員/簡介，HTML 標籤）
  └─ 集數列表：每線路一組 <li><a href="/vodplay/{id}-{lineIdx}-{epIdx}.html">第N集</a></li>

播放跳板頁 (/vodplay/ 或 /play/) 內含：
  var player_aaaa = {
    flag: "play",
    encrypt: 0,           ← 全 5 站都是 0，無加密
    trysee: 0,
    vod_data: { vod_name, vod_actor, vod_director, ... },
    url: "https://...m3u8",   ← m3u8 直連
    url_next: "...",
    link_next: "/vodplay/...",
    from: "snm3u8" | "wolong" | "wjm3u8" | ...
  }
```

關鍵發現：

1. **m3u8 直連、無加密、無 iframe、無反爬**（curl + Mobile UA 即可取）
2. URL pattern 兩種：`/vodplay/` (gimy.tw / eynytv / imaple) 與 `/play/` (momovod / 123kubo)
3. `from` 欄位為線路代碼，UI 顯示中文名需映射表

線路代碼 ↔ 顯示名（5 站完整實測 best-of-3，2026-05-08）：

| from 代碼 | UI 中文名 | imaple | momovod | gimy.tw | eynytv | 123kubo | 平均 | 覆蓋 |
|---|---|---|---|---|---|---|---|---|
| **wjm3u8** | 無盡雲 | 0.58s | 0.57s | 0.56s | 0.57s | 0.62s | **0.58s** | **5/5** ✅ |
| **snm3u8** | 索尼雲 | 0.56s | 0.60s | — | 0.57s | 0.57s | 0.58s | 4/5 |
| **1080zyk** | 優質雲 | 0.45s | 0.44s | — | — | — | **0.45s** ⚡ | 2/5 |
| **sdm3u8** | 閃電雲 | ❌ | ❌ | 0.65s | 0.68s | 0.60s | 0.64s | 3/5 |
| **jsm3u8** | 極速雲 | 0.81s | 0.80s | — | 0.80s | — | 0.80s | 3/5 |
| **wolong** | 卧龍雲 | 1.12s | 1.19s | ❌ | — | — | 1.15s 🐢 | 2/5 |

關鍵修正：
- 原規格猜測「閃電雲 = ldm3u8 / 優質雲 = yzm3u8」均錯誤 → 實測修正為 **sdm3u8 / 1080zyk**
- 原以為「閃電雲完全掛」 → 實際只有 imaple/momovod 的 **v13.fentvoss** instance HTTP 000，gimy.tw/eynytv/123kubo 正常可用
- 原以為「卧龍雲是穩定主流」 → 實測 latency 最高（1.15s）且 gimy.tw 的 wlcdn99 鏡像也掛

最終 stabilityOrder（按 **覆蓋率 + 速度** 綜合）：
```
無盡雲 → 索尼雲 → 優質雲 → 閃電雲 → 極速雲 → 卧龍雲
```
無盡雲是 5/5 全覆蓋的最穩定預設；優質雲雖最快但僅 imaple/momovod 有，故第三。

### Probe 補充：5 站 typeId 體系完全不一致（重大）

對 5 站首頁分類導航進行交叉比對，發現 typeId 體系**並不通用**：

| 標準分類 | GimyTv | gimy.tw | eynytv | imaple | momovod | 123kubo |
|---|---|---|---|---|---|---|
| 電影 | 1 | 1 | 1 | 1 | 1 | 1 |
| 劇集 | 2 | 2 | 2 | 2 | 2 | 2 |
| 動漫 | 4 | 4 | 4 | 4 | 4 | 4 |
| 綜藝 | **29** | 3 | 3 | 3 | 3 | 3 |
| 韓劇 | 20 | 23 | – | 15 | 23 | **24** |
| 陸劇 | 13 | 13 | – | 13 | 13 | 13 |
| 港劇 | **15** | **14** | – | 21 | **14** | **14** |
| 台劇 | **14** | **15** | – | 20 | **15** | **15** |
| 日劇 | 21 | 16 | – | 22 | 16 | 16 |
| 美劇 | 16 | 24 | – | 16 | 24 | **25** |
| 紀錄片 | 3 | 20 | – | 32 | 20 | 20 |
| **倫理片** | – | **39** | 外鏈 | **59** | **27** | **23** |

關鍵發現：

1. **倫理片 typeId 完全不同**（gimy.tw=39 / imaple=59 / momovod=27 / 123kubo=23），無法用單一 ID 過濾
2. **港劇/台劇 ID 互換**：GimyTv 是 14=台 / 15=港，5 新站是 14=港 / 15=台
3. **典型陷阱**：123kubo 的 typeId=23 是「倫理片」，但 gimy.tw 的 typeId=23 是「韓劇」 — 跨站傳 typeId 會誤抓
4. eynytv 子分類待 Phase 1 補抓 `/vodshow/X-...html`

---

## Goals

1. **5 站新來源整合**，總計 8 來源（含現有 3 站）
2. **多源並行搜尋**：搜尋頁同時查 8 來源，先到先顯示
3. **詳情頁跨來源 fallback**：當前來源無此影片時自動切；同名劇可手動切換來源
4. **首頁更多來源分區**：保持 GimyTv 為主流程，新來源做懶載入分區
5. **18+ 區獨立**：各站「倫理片」分類獨立呈現（per-source typeId，5 站完全不同），雙層保護（開關 + 可選 PIN）
6. **設定頁來源開關**：用戶可關閉不需要的來源

## Non-Goals

- **不整合「成人」「色色主播」外站連結**（go.ztv.tw 廣告聯盟，非站內內容）
- **不做模糊標題匹配**（避免「玉茗茶骨」誤匹配「玉茗茶骨2」）
- **不合併同名影片為單張搜尋卡**（各站集數/封面差異大）
- **不在首頁/預設搜尋/分類瀏覽顯示成人內容**（即使啟用）
- **不重做現有 GimyMax/GimyTv/Movieffm scraper**（保持現狀）

---

## Architecture

### 來源類型擴充

```kotlin
enum class SourceType(val displayName: String) {
    GIMYTV("GimyTv"),       // 現有
    GIMYMAX("GimyMax"),     // 現有
    MOVIEFFM("Movieffm"),   // 現有
    GIMY_TW("Gimy"),        // 新增：gimy.tw
    EYNY_TV("Eyny"),        // 新增：eynytv.com
    IMAPLE_TV("Imaple"),    // 新增：imaple.tv
    MOMOVOD("Momo"),        // 新增：momovod.app
    KUBO123("Kubo")         // 新增：123kubo.net
}
```

### Scraper 結構

```
data/scraper/
  ├─ SiteSource (interface, 既有)
  │
  ├─ GimyMaxSource (既有，不動，使用 var player_data JSON)
  ├─ GimyTvSource (既有，不動，使用 var player_data JSON)
  ├─ MovieffmSource (既有，不動)
  │
  └─ MacCmsListBasedSource (新基類，list-based 集數 + 二級頁 m3u8)
      ├─ abstract val baseUrl: String
      ├─ abstract val sourceType: SourceType
      ├─ abstract val categoryMap: SiteCategoryMap   // 每站獨立 typeId 對應表
      ├─ open val detailUrlPattern: String     // "/voddetail/{id}.html"
      ├─ open val playUrlPattern: String       // "/vodplay/{id}-{lineIdx}-{epIdx}.html"
      │
      ├─ fetchVodList(category: StandardCategory, page) — 內部轉換為該站 typeId
      ├─ fetchVodDetail(vodId) — 解析 detail 頁
      │   ├─ Title / year / actor / director / area / desc
      │   └─ Episodes：每線路一組 [LineMeta + List<EpisodeRef>]
      │       └─ EpisodeRef = (vodId, lineIdx, epIdx, title) — 此時尚未取 m3u8
      ├─ resolvePlayUrl(vodId, lineIdx, epIdx) — 拉 vodplay 頁，extract player_aaaa.url
      └─ search(keyword) — 解析搜尋頁，過濾掉 categoryMap.adult 的結果（除非從 18+ 區呼叫）

// 標準分類 enum（UI 與跨站邏輯使用）
enum class StandardCategory {
    MOVIE, SERIES, ANIME, VARIETY,
    KOREAN, CHINESE, HK, TAIWAN, JAPANESE, AMERICAN,
    DOCUMENTARY, ADULT
}

// 每站 typeId 對應表
data class SiteCategoryMap(
    val movie: Int = 1,
    val series: Int = 2,
    val variety: Int = 3,
    val anime: Int = 4,
    val korean: Int,
    val chinese: Int,
    val hk: Int,
    val taiwan: Int,
    val japanese: Int,
    val american: Int,
    val documentary: Int,
    val adult: Int   // -1 表示無倫理片或外鏈
) {
    fun typeIdFor(category: StandardCategory): Int = when (category) {
        StandardCategory.MOVIE -> movie
        StandardCategory.SERIES -> series
        StandardCategory.VARIETY -> variety
        StandardCategory.ANIME -> anime
        StandardCategory.KOREAN -> korean
        StandardCategory.CHINESE -> chinese
        StandardCategory.HK -> hk
        StandardCategory.TAIWAN -> taiwan
        StandardCategory.JAPANESE -> japanese
        StandardCategory.AMERICAN -> american
        StandardCategory.DOCUMENTARY -> documentary
        StandardCategory.ADULT -> adult
    }
}

// 各 Source 提供 categoryMap
val gimyTwMap   = SiteCategoryMap(korean=23, chinese=13, hk=14, taiwan=15,
                                  japanese=16, american=24, documentary=20, adult=39)
val eynyTvMap   = SiteCategoryMap(korean= 0, chinese= 0, hk= 0, taiwan= 0,
                                  japanese= 0, american= 0, documentary= 0, adult=27)
                                  // Phase 1 補 probe 後填入
val imapleMap   = SiteCategoryMap(korean=15, chinese=13, hk=21, taiwan=20,
                                  japanese=22, american=16, documentary=32, adult=59)
val momovodMap  = SiteCategoryMap(korean=23, chinese=13, hk=14, taiwan=15,
                                  japanese=16, american=24, documentary=20, adult=27)
val kubo123Map  = SiteCategoryMap(korean=24, chinese=13, hk=14, taiwan=15,
                                  japanese=16, american=25, documentary=20, adult=23)

// 現有來源也統一接入（GimyTv/GimyMax）
val gimyTvMap   = SiteCategoryMap(korean=20, chinese=13, hk=15, taiwan=14,
                                  japanese=21, american=16, documentary=3, adult=-1, variety=29)
      │
      ├─ GimyTwSource (baseUrl=https://gimy.tw, /vodplay/)
      ├─ EynyTvSource (baseUrl=https://eynytv.com, /vodplay/)
      ├─ ImapleTvSource (baseUrl=https://imaple.tv, /vodplay/)
      ├─ MomovodSource (baseUrl=https://momovod.app, /play/)
      └─ Kubo123Source (baseUrl=https://123kubo.net, /play/)
```

### EndpointResolver 擴充

```kotlin
// EndpointResolver 加入 5 站 baseUrl，支援遠端 config override
private val DEFAULT_ENDPOINTS = mapOf(
    SourceType.GIMYTV to "https://gimytv.ai",
    SourceType.GIMYMAX to "https://gimymax.com",
    SourceType.MOVIEFFM to "https://movieffm.net",
    SourceType.GIMY_TW to "https://gimy.tw",
    SourceType.EYNY_TV to "https://eynytv.com",
    SourceType.IMAPLE_TV to "https://imaple.tv",
    SourceType.MOMOVOD to "https://momovod.app",
    SourceType.KUBO123 to "https://123kubo.net"
)
```

### 線路穩定度排序

線路代碼 → 穩定度排序（待 Phase 2 實測後微調）：

```kotlin
// MacCmsListBasedSource stabilityOrder
private val stabilityOrder = listOf(
    "wolong",     // 卧龍雲
    "snm3u8",     // 索尼雲
    "wjm3u8",     // 無盡雲
    "jsm3u8",     // 極速雲
    "ldm3u8",     // 閃電雲
    "yzm3u8"      // 優質雲
)
```

---

## 搜尋頁聚合

### 並行策略

```
SearchViewModel:
  search(keyword) {
    enabled_sources = settings.enabledSources()  // user toggle 過濾
    val results = MutableStateFlow(emptyList<VodSearchResult>())

    enabled_sources.forEach { source ->
      viewModelScope.launch {
        try {
          withTimeout(5_000) {
            val items = source.search(keyword).filter { it.typeId != 27 }  // 過濾 18+
            results.update { it + items.map { vod -> VodSearchResult(vod, source.type) } }
          }
        } catch (_: Exception) { /* ignore single source failure */ }
      }
    }

    progressFlow.update { "已收到 ${received}/${total}" }
  }
```

### 排序

主排序：標題匹配度（精確 contains 為先，再按 startsWith）
次排序：來源 stability（GimyTv > GimyMax > Imaple > Gimy.tw > EynyTv > Momovod > 123Kubo > Movieffm）

### UI 結構

```
[搜尋輸入框]

[篩選 chip] [全部 36] [GimyTv 8] [Imaple 7] [Momovod 6] ...

進度：「正在搜尋... 5/8 來源已回應」(全完成後消失)

LazyVerticalGrid {
  VodCard(vod, sourceBadge=源徽章)
}
```

### Cache

- DataStore 快取最近 10 個關鍵字結果（TTL 60 秒）
- 切換 chip 不重打網路
- 強制刷新需重新搜尋

---

## 詳情頁聚合

### 跨來源匹配規則

```
DetailViewModel.loadVod(vodId, sourceType):
  // Phase 1: 主流程，立即顯示當前來源
  uiState = current_source.fetchVodDetail(vodId)

  // Phase 2: 背景任務，並行查其他 7 來源
  launch {
    val title = uiState.title
    val year = uiState.year
    val matches = mutableListOf<CrossSourceMatch>()

    other_sources.forEach { src ->
      launch {
        try {
          withTimeout(5_000) {
            val results = src.search(title)
            results.find { normalizeTitle(it.title) == normalizeTitle(title) && it.year == year }
              ?.let { matches.add(CrossSourceMatch(src.type, it.id)) }
          }
        } catch (_: Exception) { /* ignore */ }
      }
    }

    uiState.update { it.copy(crossSourceMatches = matches) }
  }
```

`normalizeTitle`：去除空白、標點、全形/半形差異。

### Fallback 規則

| 場景 | 行為 |
|---|---|
| 當前來源 fetch 成功 | 顯示當前來源資料，背景查同名跨來源切換選項 |
| 當前來源 404 / 影片下架 | 自動依 stability 順序試其他 7 來源，找到後切換並提示用戶 |
| 全部來源都找不到 | 顯示錯誤頁 |

### UI 結構

```
影片封面 + 標題 + 年份 + 簡介 + 演員（取當前來源）

來源切換：[Momovod ▼]   ← 啟用條件：crossSourceMatches.isNotEmpty()
  下拉選項：
    ├─ Momovod (30 集, 6 線路) ← 當前
    ├─ GimyTv (28 集, 4 線路)
    └─ Imaple (30 集, 6 線路)

線路選擇：[索尼雲] [極速雲] [無盡雲] ...
集數列表：[第1集] [第2集] ... [第30集]
```

切換來源時：重置線路與集數選擇，重新拉該來源詳情頁。

---

## 首頁聚合

### Layout

```
首頁
├─ 繼續觀看（既有）
├─ GimyTv 主分類橫排（10 行：韓劇/陸劇/美劇/...）   ← 立即載入
├─ ───── 分隔線 ─────
└─ 更多來源 (預設展開)                            ← 滾動到時 fetch
   ├─ Imaple 推薦                                ← LazyEffect on visible
   ├─ Momovod 陸劇精選                           ← LazyEffect on visible
   ├─ 123Kubo 新作                                ← LazyEffect on visible
   ├─ Gimy.tw 推薦                                ← LazyEffect on visible
   └─ EynyTv 推薦                                 ← LazyEffect on visible
```

### 懶載入機制

```kotlin
@Composable
fun MoreSourceRow(sourceType: SourceType) {
    val visible = rememberFirstVisible()  // intersection observer 等價
    val items by produceState<List<Vod>>(emptyList(), visible) {
        if (visible && value.isEmpty()) {
            value = repository.fetchSourceHomeList(sourceType, typeId = 1).items
        }
    }
    if (items.isNotEmpty()) ContentRow(...) else PlaceholderRow()
}
```

### 跳過已停用來源

`MoreSourceRow` 從 `settings.enabledSources()` 取啟用清單，未啟用的來源跳過渲染。

---

## 18+ 區設計

### State

DataStore preferences：

| Key | Type | Default | 用途 |
|---|---|---|---|
| `adult_content_enabled` | Boolean | false | 主開關 |
| `adult_content_pin_required` | Boolean | false | 是否啟用 PIN 鎖 |
| `adult_content_pin_hash` | String? | null | PIN 的 SHA-256 雜湊 |

Session 暫存：

| Key | Type | Lifecycle | 用途 |
|---|---|---|---|
| `adult_content_unlocked` | Boolean | per cold start | 本次冷啟動是否已解鎖 |

### Flow

```
冷啟動 → unlocked = false

設定頁
├─ 顯示成人內容 [Switch]                  ← 主開關
└─ 啟用 PIN 鎖 [Switch + 設定 PIN]        ← 可選
    └─ 設定 PIN 對話框 (4 位數字)

分類瀏覽頁
├─ 11 個現有分類
└─ 「18+」入口                            ← 條件：enabled == true
    └─ 點擊：
        ├─ pin_required && !unlocked
        │   → 彈出 PIN 輸入框 → 驗證
        │   → 通過：unlocked = true → 進入列表
        │   → 失敗：3 次後鎖 5 分鐘
        └─ else → 直接進入

18+ 列表頁
├─ 來源 tab（僅顯示 categoryMap.adult > 0 的來源 = gimy.tw / eynytv / imaple / momovod / 123kubo）
├─ 每個 tab 查該站的 categoryMap.adult typeId
│   ├─ gimy.tw  → typeId=39
│   ├─ eynytv   → typeId=27（待 Phase 1 補 probe 確認）
│   ├─ imaple   → typeId=59
│   ├─ momovod  → typeId=27
│   └─ 123kubo  → typeId=23
└─ 點擊影片 → 詳情頁 → 播放（與一般影片同流程）

非 18+ 區流程（永遠過濾各站的 adult typeId）
├─ 首頁分類橫排：跳過 categoryMap.adult typeId
├─ 預設搜尋：每站結果中濾掉 typeId == categoryMap.adult 的項
├─ 分類瀏覽：不顯示 18+ 入口（僅在 enabled 時顯示）
└─ 詳情頁跨來源匹配：不匹配 typeId == categoryMap.adult 的影片

⚠ 關鍵原則：**過濾邏輯必須 per-source 套用各站 adult typeId**，不能用單一硬編碼 ID（123kubo 的 23 = 倫理片，但 gimy.tw 的 23 = 韓劇，硬編碼會誤殺）
```

### PIN 輸入元件（TV 遙控器友善）

```
┌─────────────────────┐
│   請輸入 4 位 PIN    │
│                     │
│   ●  ●  ○  ○        │
│                     │
│  [1] [2] [3]        │
│  [4] [5] [6]        │
│  [7] [8] [9]        │
│      [0]            │
│                     │
│  剩餘嘗試：3 次      │
└─────────────────────┘
```

- D-pad 上下左右導航數字
- 中央 OK 鍵輸入
- BACK 鍵刪除一位
- 滿 4 位自動驗證

### PIN 安全

- 儲存：SHA-256 雜湊，不存明文
- 失敗鎖：3 次失敗鎖 5 分鐘（防止暴力破解）
- 重設：需先輸入舊 PIN（無法繞過）；忘記 PIN 需「重置成人內容設定」（清空所有 adult 偏好，等同關閉開關重來）

---

## 設定頁擴充

```
設定
├─ 一般
│  ├─ 啟動畫面
│  └─ 預設首頁定位
│
├─ 來源管理   ← 新增區塊
│  ├─ 啟用來源（Multi-select）
│  │   ☑ GimyTv          (主來源)
│  │   ☑ GimyMax
│  │   ☑ Movieffm
│  │   ☑ Imaple
│  │   ☑ Momovod
│  │   ☑ 123Kubo
│  │   ☑ Gimy.tw
│  │   ☑ EynyTv
│  └─ 來源優先順序（drag to reorder, 進階）
│
├─ 成人內容   ← 新增區塊
│  ├─ 顯示成人內容 [Switch]
│  ├─ 啟用 PIN 鎖 [Switch]
│  ├─ 設定 / 變更 PIN
│  └─ 重置成人內容設定 (危險動作)
│
├─ 顯示
│  └─ ...（既有）
└─ 關於
   └─ ...（既有）
```

---

## Phase 計畫

### Phase 1：Scraper 基礎建設（2-3 天）

| Task | 描述 |
|---|---|
| 1.1 | 補 probe 5 站的 list 頁 / search 頁 URL pattern |
| 1.2 | 擴充 SourceType enum + endpoint 定義 |
| 1.3 | 新建 `MacCmsListBasedSource` 抽象類 |
| 1.4 | 實作 5 個 site source（共享 list-based 集數提取） |
| 1.5 | DI 註冊（Hilt module） |
| 1.6 | 整合測試：每站 fetchVodList / fetchVodDetail / resolvePlayUrl 通過 |

### Phase 2：聚合層（1-2 天）

| Task | 描述 |
|---|---|
| 2.1 | VodRepositoryImpl 多源並行 search（8 來源 + 5s 超時） |
| 2.2 | DetailViewModel 背景跨來源匹配（標題+年份精確） |
| 2.3 | 線路 stabilityOrder 實測（測 5-10 部影片各線路 m3u8 連線速度） |
| 2.4 | Search/Detail cache（DataStore TTL 60s） |
| 2.5 | 補抓 eynytv 子分類（韓/陸/港/台/日/美劇 typeId）；完成各站 SiteCategoryMap；UI 改用 StandardCategory enum 觸發查詢 |

### Phase 3：UI 整合（2-3 天）

| Task | 描述 |
|---|---|
| 3.1 | HomeScreen「更多來源」分區（懶載入） |
| 3.2 | SearchScreen 來源 chip 篩選 + 增量呈現 + 進度提示 |
| 3.3 | DetailScreen 來源切換下拉 |
| 3.4 | 設定頁「來源管理」區塊（啟用清單） |

### Phase 4：18+ 區（1-2 天）

| Task | 描述 |
|---|---|
| 4.1 | DataStore 加入 adult_content_* preferences |
| 4.2 | 設定頁「成人內容」區塊（開關 + PIN 設定） |
| 4.3 | PinInputDialog 元件（TV 遙控器友善） |
| 4.4 | 「分類瀏覽」18+ 入口（條件顯示） |
| 4.5 | 18+ 列表頁（8 來源 tab） |
| 4.6 | 全 App per-source adult typeId 過濾（首頁/預設搜尋/分類瀏覽/跨來源匹配，使用各站 categoryMap.adult） |
| 4.7 | PIN 失敗鎖（3 次 → 5 分鐘） |

### Phase 5：測試與發版（1 天）

| Task | 描述 |
|---|---|
| 5.1 | 多源穩定性測試（網路差/單站掛掉的 fallback 行為） |
| 5.2 | PIN 鎖場景測試（冷啟動/熱啟動/應用切換） |
| 5.3 | 整合 v2.1.0 release（versionCode 210, versionName 2.1.0） |
| 5.4 | release.sh 執行 + GitHub Release |

**預估總工時：7-10 天**

---

## Testing 策略

### 單元測試

- 每個 SiteSource 的 HTML 解析（mock HTML fixture）
- normalizeTitle / cross-source matching 邏輯
- PIN 雜湊驗證 / 失敗鎖計數

### 整合測試

- fetchVodList 對 5 站的真實 endpoint
- resolvePlayUrl 取得有效 m3u8
- 8 源並行 search 的超時與部分失敗處理

### UI 測試（手動）

- TV 遙控器：8 來源切換、PIN 輸入、來源 chip 切換
- 邊界：所有來源都關閉時的 UI、單站掛掉時 fallback

---

## Risks & Mitigations

| 風險 | 緩解 |
|---|---|
| 5 新站結構日後改版 | EndpointResolver 已支援遠端 config，可推送 baseUrl 變更；scraper 邏輯改版需新版 App |
| 8 源並行打網路導致 TV 卡頓 | 主流程只打 1 源（GimyTv）；其他來源懶載入或搜尋時並行 + 超時 |
| 線路 stability 預設不準 | Phase 2.3 實測；提供「自動選擇最快線路」選項作為 fallback |
| typeId 體系不一致（已驗證） | 採 `SiteCategoryMap` per-source 對應表 + UI 用 `StandardCategory` enum；嚴禁硬編碼數字 typeId（如 123kubo 的 23=倫理片但 gimy.tw 的 23=韓劇，硬編碼會誤殺） |
| PIN 忘記 | 提供「重置成人內容設定」按鈕（清空所有 adult 偏好，包含 enabled） |
| 兒童誤觸 18+ | 雙層保護：開關預設關 + 可選 PIN；首頁/搜尋/分類預設都過濾各站 adult typeId（per-source） |

---

## Open Questions（規劃時待釐清）

1. **線路 stability 排序**：snm3u8 / wolong / wjm3u8 / ldm3u8 / jsm3u8 / yzm3u8 哪個最穩？需 Phase 2.3 實測。
2. **typeIdMap**：5 站的 typeId 體系是否完全一致？需 Phase 2.5 對 list 頁逐一驗證。
3. **「分類瀏覽」是否也要分來源 tab**？目前設計為單一 typeId 跨來源混排，是否要讓用戶能單獨看「Momovod 的陸劇」？暫定不做，等用戶反饋。
4. **跨來源切換時是否保留集數位置**？例如用戶看到 Momovod 第 5 集，切到 GimyTv 想接續看第 6 集 — 是否自動定位？暫定不做（兩站集數編號可能錯位），切到新來源從第 1 集開始。

---

## Acceptance Criteria

v2.1.0 發版需滿足：

- [ ] 8 來源全部可正常 fetch list / detail / play
- [ ] 搜尋頁可顯示 8 來源混合結果，含來源 chip 篩選
- [ ] 詳情頁可跨來源切換（同名劇）
- [ ] 首頁「更多來源」懶載入分區可見
- [ ] 設定頁可啟用/停用來源、設定 PIN
- [ ] 18+ 區默認隱藏；啟用後可見入口；啟用 PIN 後需通過驗證
- [ ] 18+ 列表頁能查到 5 站各自的倫理片（gimy.tw=39 / eynytv 待補 / imaple=59 / momovod=27 / 123kubo=23）
- [ ] 首頁/預設搜尋/分類瀏覽永不顯示各站 adult typeId 內容（per-source 過濾）
- [ ] 單站掛掉不影響其他來源
- [ ] APK 簽章使用 release.keystore，可覆蓋升級 v2.0.4
