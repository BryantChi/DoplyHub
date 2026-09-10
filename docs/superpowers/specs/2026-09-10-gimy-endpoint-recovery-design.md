# Gimy 失效來源修復（第一輪）

**日期**: 2026-09-10
**狀態**: 設計待審
**背景**: 例行 endpoint 稽核發現 GIMYTV / GIMYMAX 兩個來源全面失效。
完整證據見 [`docs/2026-09-10-endpoint-health-audit.md`](../../2026-09-10-endpoint-health-audit.md)。

---

## 1. 問題陳述

`endpoints.json` 中兩個 Gimy 來源皆已失效，且都是**靜默失效**（HTTP 200、解析 0 筆）：

| Source | 現行 endpoint | 失效原因 |
|---|---|---|
| GIMYTV | `gimyplus.com` | 站台轉型為線路索引工具，`/type/` 導回首頁，無 `/vod/` 詳情頁 |
| GIMYMAX | `gimy01.co` | 301 至 `gitube.tv`，該站版型與路徑全換 |

距離上一次同性質事故（2026-06-02，見 `2026-06-02-scraper-resilience-redesign.md`）僅三個月，
**同一齣戲重演**。當時導入的 parser-based 健康探測正確判定了本次失效，
卻因每個 source 只有單一候選網址而無法自動切換（詳見稽核報告第 7 節）。
本輪不處理該機制缺陷，僅先恢復可用性。

---

## 2. 範圍

**做**：
1. GIMYTV endpoint 換至 `gimytv.me`。
2. GIMYMAX endpoint 換至 `gitube.tv`，並修正其路徑與解析。
3. 兩者共用同一份 parser（路徑 token 參數化），刪除重複的 `GimyMaxParser`。

**不做**（留待後續輪次）：
- 多網域候選與 `endpoints.json` schema 擴充（第二輪）
- 健康狀態可見性：設定頁、列表失效提示（第二輪）
- 收藏 / 觀看紀錄失效標記與清理（第三輪）
- Cloudflare 繞過（第四輪）
- `gimyai.tw`（`gimytv.me` 的真鏡像）暫不納入 —— 其版型為新版 B，需第二輪的 profile 機制才能承載

---

## 3. 設計

### 3.1 核心觀察

`gitube.tv` 與 `gimytv.me` 是**同一套系統**：列表卡片 HTML 逐字相同、
詳情頁同為 `div.playlist-block` + `.playlist-block__title`，
差異可完全歸納為三個路徑 token：

| | gimytv.me（GIMYTV） | gitube.tv（GIMYMAX） |
|---|---|---|
| 列表 | `/type/{id}.html` | `/browse/{id}.html` |
| 詳情 | `/vod/{id}.html` | `/title/{id}.html` |
| 劇集 | `/ep/{vod}-{sid}-{ep}.html` | `/watch/{vod}-{sid}-{ep}.html` |

而現行 `GimyMaxParser` 對應的舊版型（`a.poster` + `.sources .source[data-id]` + `/eps/`）
**已完全不存在於任何 Gimy 網域**。因此不是「修 GimyMaxParser」，而是讓 GIMYMAX 改用
已被驗證正確的 GimyTv 解析邏輯，只替換路徑 token。

### 3.2 Parser 參數化

`GimyTvParser`（`object`）改為 `GimyParser`（`class`），建構時接收來源與路徑設定：

```kotlin
/** Path tokens that differ between Gimy mirrors sharing the same HTML template. */
data class GimyPaths(
    val list: String,      // "/type"  | "/browse"  — URL building only
    val detail: String,    // "/vod"   | "/title"
    val episode: String,   // "/ep"    | "/watch"
)

class GimyParser(
    private val sourceType: SourceType,
    private val paths: GimyPaths,
) {
    private val vodIdRegex = Regex("${Regex.escape(paths.detail)}/(\\d+)\\.html")
    private val epRegex = Regex("${Regex.escape(paths.episode)}/\\d+-(\\d+)-(\\d+)\\.html")
    private val cardSelector = "a.card__thumb[href*=${paths.detail}/]"
    …
}
```

`GimyPaths` 是一份來源設定，兩邊各取所需：Source 用 `list` / `detail` / `episode`
組 URL，parser 只用 `detail` / `episode` 導出比對規則。設定物件保持完整一份而非
拆成兩個，是因為三個 token 描述的是同一件事（該鏡像的路徑命名），
拆開反而讓「換一個鏡像」需要同時改兩處。

三處寫死的常數（`vodIdRegex`、`epRegex`、列表卡片選擇器）改由 `paths` 導出，
`SourceType.GIMYTV` 的硬編碼改為 `sourceType` 欄位。其餘解析邏輯**完全不動** ——
`aria-label` / `card__badge` / `card__title` / `playlist-block__title` /
`stabilityOrder` / `extractMeta` 均已在兩站實測通過。

Source 端以 Hilt 注入不變，parser 實例在 class body 直接建立（無外部依賴，
不需進 DI 圖）：

```kotlin
class GimyMaxSource @Inject constructor(
    private val client: OkHttpClient,
    private val endpointResolver: EndpointResolver,
) : SiteSource {
    private val paths = GimyPaths(list = "/browse", detail = "/title", episode = "/watch")
    private val parser = GimyParser(SourceType.GIMYMAX, paths)
    …
}
```

`GimyMaxParser` 刪除。

### 3.3 player 變數：以 regex 統一，不參數化

兩站的播放頁變數名不同，但無需額外設定：

| 網域 | 播放頁內容 |
|---|---|
| gimytv.me | `player_data={"flag":"play",…}` |
| gitube.tv | `var player_aaaa={"flag":"play",…}`，另有 `player_data=player_aaaa;` |

現行 `indexOf("player_data=")` 在 `gitube.tv` 會命中 `player_data=player_aaaa;`，
接著找到的 `{` 落在 33 KB 外的一段 JS，`JSONObject` 拋 JSONException。

改以下列 regex 定位，要求 `=` 後緊接 `{`，天然排除變數指派：

```kotlin
// gitube.tv assigns `player_data=player_aaaa;` alongside the real `var player_aaaa={...}`.
// Requiring `{` right after `=` skips the alias and lands on the JSON either way.
private val playerVarRegex = Regex("""player_(?:data|aaaa)\s*=\s*\{""")
```

對 `gimytv.me`（`player_data={`）、`gitube.tv`（`player_aaaa={`）、
`gimyai.tw`（`var player_data={`）三站皆成立。後續的大括號配對與
base64 解密邏輯不變。

`GimyTvSource` 與 `GimyMaxSource` 現有兩份幾乎一字不差的 `parsePlayerData`，
一併抽為共用函式。

### 3.4 endpoint 更新

`endpoints.json` 與 `EndpointResolver.DEFAULTS` 同步：

```json
"gimytv":  ["https://gimytv.me"],
"gimymax": ["https://gitube.tv"],
```

保持單一候選（陣列多值需第二輪的 profile 機制支撐，見稽核報告 4.3：
`gitube.tv` 與 `gimytv.me` 的 id 空間會指向不同影片，**不可互為備援**）。

---

## 4. 變更清單

| 檔案 | 變更 |
|---|---|
| `endpoints.json` | `gimytv` → `gimytv.me`；`gimymax` → `gitube.tv` |
| `EndpointResolver.kt` | `DEFAULTS` 兩筆同步；更新排除說明註解 |
| `parser/GimyTvParser.kt` → `parser/GimyParser.kt` | `object` 改 `class`，接收 `sourceType` + `GimyPaths` |
| `parser/GimyMaxParser.kt` | 刪除 |
| `GimyTvSource.kt` | 持有 `GimyParser(GIMYTV, GimyPaths("/type", "/vod", "/ep"))`；`parsePlayerData` 改用共用函式 |
| `GimyMaxSource.kt` | 持有 `GimyParser(GIMYMAX, GimyPaths("/browse", "/title", "/watch"))`；列表 / 詳情 URL 改用 `paths` |
| `GimyTvParserTest.kt` → `GimyParserTest.kt` | 涵蓋兩組 paths |
| `GimyMaxParserTest.kt` | 刪除（其 fixture 為已不存在的舊版型） |

---

## 5. 測試策略

沿用專案既有慣例（inline HTML fixture + Jsoup + Truth）。
**fixture 取自 2026-09-10 實抓的頁面**，非憑空撰寫。

| 測試 | 驗證意圖 |
|---|---|
| `parses gimytv list with /vod/ paths` | GIMYTV 路徑設定能解析 `gimytv.me` 卡片，取得 id / 標題 / 封面 / 集數狀態 |
| `parses gitube list with /title/ paths` | 同一 parser 換 paths 後能解析 `gitube.tv`，且 `sourceType` 為 GIMYMAX |
| `gitube paths reject gimytv urls` | paths 具有隔離性 —— 用 GIMYMAX 設定解析 `gimytv.me` HTML 應得 0 筆，確保 token 真的生效而非選擇器過寬 |
| `parses detail playlist with episode token` | 兩組 paths 各自解出線路名與集數連結 |
| `player regex skips alias assignment` | 對 `player_data=player_aaaa;` + `var player_aaaa={…}` 的頁面，取到的是 JSON 而非別名 |
| `player regex handles plain player_data` | `gimytv.me` 形態仍正常 |

第三項是這輪的關鍵測試：它會在「有人為了寬鬆而把選擇器改回 `[href*=/]`」時失敗，
正是本次事故的根因形態。

---

## 6. 已知限制

1. **搜尋功能不會恢復**。Gimy 全家族的搜尋路徑（`/search/`、`/find/`）皆掛
   Cloudflare Managed Challenge，回 403 `Just a moment`。列表 / 詳情 / 播放不受影響。
   這不是本輪造成的退步（`gimyplus.com` 現況亦同），但修完後搜尋仍不可用。
   與 JABLE_TV 為同一技術問題，第四輪一併處理。
2. **`gimyai.tw` 未納入**。它是 `gimytv.me` 的真鏡像（id 相容，可作真備援），
   但版型為新版 B（`a.poster` / `div.block` + `.route-title` / `/genre/` / `/detail/` / `/play/`），
   需第二輪的 profile 機制才能承載。
3. **既有收藏與觀看紀錄不受影響**。兩條血脈的 id 空間各自延續
   （`gimytv.ai` → `gimytv.me`、`gimy01.tv` → `gitube.tv`），舊記錄仍指向正確影片。

---

## 7. 驗收標準

1. `./gradlew test` 全綠，含上述新增測試。
2. `GimyParser` 以 GIMYTV paths 解析實抓的 `gimytv.me/type/2.html` ≥ 5 筆
   （實測基準 198 筆）。
3. `GimyParser` 以 GIMYMAX paths 解析實抓的 `gitube.tv/browse/2.html` ≥ 5 筆
   （實測基準 162 筆）。
4. 兩個 source 的 `probeListCount()` 在真實網路下皆回傳 ≥ `MIN_HEALTHY_ITEMS`。
5. 專案內不再有任何對 `GimyMaxParser` 的引用。
