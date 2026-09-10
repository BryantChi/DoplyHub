# 通過 Cloudflare Managed Challenge（第四輪）

**日期**: 2026-09-10
**狀態**: 設計待審
**背景**: Gimy 全家族搜尋路徑與 jable.tv 全站掛 Cloudflare Managed Challenge，
純 OkHttp 一律回 403。稽核見 [`docs/2026-09-10-endpoint-health-audit.md`](../../2026-09-10-endpoint-health-audit.md)。

---

## 1. 問題陳述

| 目標 | 現況 | 影響 |
|---|---|---|
| `gimytv.me/search/` | 403 challenge | GIMYTV 搜尋不可用 |
| `gitube.tv/search/` | 403 challenge | GIMYMAX 搜尋不可用 |
| `gimyai.tw/find/` | 403 challenge | （第二輪才會納入） |
| `jable.tv/*` | 403 challenge（全站） | JABLE_TV 完全不可用 |
| 上述各站列表 / 詳情 / 播放 | 200 正常 | 不受影響 |

**GIMYMAX 的連帶問題**：`gimyMaxSource` 在 App 內僅出現於 `searchOrder()` 與
`searchAllSources()`（`VodRepositoryImpl.kt:206,236`）—— 首頁走 `getGimyHomeRows()`
只用 `gimyTvSource`，分類瀏覽亦然。**搜尋是 GIMYMAX 的唯一入口**，
因此第一輪修好的 gitube.tv parser 目前完全碰不到，必須本輪解開才有實效。

### 1.1 challenge 性質（2026-09-10 實測）

以真實 Chrome 造訪三個目標，challenge 均在 6–8 秒內**自動**通過，全程無需人工互動：

| 目標 | 結果 |
|---|---|
| `gimytv.me/search/財閥…` | `isChallenge:false`，20 筆結果 |
| `gitube.tv/search/財閥…` | `isChallenge:false`，20 筆結果 |
| `jable.tv/hot/` | `isChallenge:false`，24 支影片 |

這是 Managed Challenge（JS 運算）而非互動式驗證碼，故可由 WebView 自動完成。

`document.cookie` 僅見 `PHPSESSID`（jable 另有 `kt_ips`、`kt_tcookie`）——
`cf_clearance` 為 HttpOnly，JS 讀不到，但 Android `CookieManager.getCookie()`
走 native 層可取得。

---

## 2. 範圍

**做**：
1. `CloudflareGateway`：偵測 challenge → WebView 解題 → 取 `cf_clearance` → 重試。
2. OkHttp 接上 CookieJar（**目前完全沒有**，見 `NetworkModule.kt`）與持久化。
3. User-Agent 對齊：受 CF 影響的請求改用 WebView 的真實 UA。
4. Gimy 搜尋 parser（`article.search-item` 版型）。
5. WebView 缺席時的降級。

**不做**：
- 不動 MacCms / 5278 / XNXX 等未受 CF 影響來源的 UA 與 cookie 行為（維持現狀）。
- 不做預先 warm-up：僅在遭遇 403 時才啟動 WebView（見 3.2）。
- 不處理互動式驗證碼（若 Cloudflare 日後升級為需人工點選，本方案失效，見 §6）。

---

## 3. 設計

### 3.1 CloudflareGateway

```
OkHttp 請求 ──→ 200 ──→ 正常解析
     │
     └─→ 403 且 body 含 challenge 特徵
              │
              ├─→ 同一 host 的解題以 Mutex 串行（避免 10 個分類同時開 WebView）
              ├─→ 隱藏 WebView 載入該 URL，輪詢至 cf_clearance 出現或逾時 20s
              ├─→ CookieManager.getCookie(host) → 存入 CookieJar + DataStore
              └─→ 重試原請求一次（僅一次，避免無限循環）
```

challenge 偵測條件：`code == 403` **且** body 含 `cf_chl_opt` 或 `Just a moment`。
兩者皆須成立 —— 單看 403 會把站方真正的權限錯誤誤判成 challenge。

WebView 必須在主執行緒建立，故 gateway 以 `withContext(Dispatchers.Main)` 包裹，
解題完成後回到 IO 續行。

### 3.2 取得時機：遇 403 才解，但持久化

不做啟動預熱 —— 多數使用者開 App 只看首頁，首頁不受 CF 影響，預熱等於白跑一次
WebView。改為遭遇 403 時才解題，並將 cookie 寫入 DataStore：下次開 App 直接沿用，
真正過期（再次 403）才重解。實務上只有安裝後第一次搜尋會等那 6–8 秒。

DataStore 儲存 `host → cookie 字串 + 取得時間`。不預設 TTL —— `cf_clearance` 的
有效期由 Cloudflare 決定且會變動，與其猜測不如以「再次 403」作為唯一失效訊號。

### 3.3 User-Agent 方向：OkHttp 沿用 WebView，而非反過來

`cf_clearance` 綁定 IP + User-Agent。直覺做法是把 WebView 的 UA 設成 OkHttp 現用的
`Mozilla/5.0 (Linux; Android 13; TV) AppleWebKit/537.36`，但那會讓 Cloudflare 看到
一個「自稱 X、指紋卻是 Y」的瀏覽器，是明顯的自動化特徵。

因此反過來：啟動時以 `WebSettings.getDefaultUserAgent(context)` 取得本機 WebView 的
真實 UA，OkHttp 沿用之。WebView 解題時呈現完全真實的指紋，OkHttp 後續請求的 UA 與
cookie 也就對得上。

改動範圍限縮在受 CF 影響的路徑：

| 位置 | 現況 | 變更 |
|---|---|---|
| `NetworkModule.kt:42` | 寫死 TV UA | 改為注入的 WebView UA（Gimy 家族走此處） |
| `EmbeddedHlsSource.kt:50` | 自有 UA（`private val`） | 改為 `protected open val`，父類預設值不變 |
| `JableTvSource.kt` | 繼承父類 UA | **覆寫**為注入的 WebView UA |
| `XnxxSource.kt` | 繼承父類 UA | **不動** —— 與 Jable 同為 `EmbeddedHlsSource` 子類，故 UA 必須做成可覆寫而非直接改父類 |
| `MacCmsListBasedSource.kt:79`、`Forum5278Source.kt:36`、`DoplyApp.kt:64`(Coil) | 自有 UA | **不動** —— 未受 CF 影響，改動只會擴大爆炸半徑 |

UA 取得失敗時（WebView 缺席）退回現行寫死值。

### 3.4 降級：WebView 不可用

Google 認證的 Android TV 均內建 WebView，但非認證機種可能缺席。
啟動時檢查一次（`WebSettings.getDefaultUserAgent` 拋 `AndroidRuntimeException`
或 `PackageManager` 查不到 WebView 實作），不可用時 gateway 直接放行 403，
行為與現行完全相同（搜尋失敗、Jable 不可用），不崩潰、不重試。

### 3.5 Gimy 搜尋 parser

搜尋頁與列表頁版型不同（2026-09-10 實測）：

```html
<article class="search-item">
  <a href="/vod/481112.html" class="search-item__thumb" aria-label="財閥X刑警第二季">
    <img src="https://imgs.1777cdn.com/upload/vod/…jpg" alt="財閥X刑警第二季">
  </a>
  <div class="search-item__body">
    <h2 class="search-item__title"><a href="/vod/481112.html">財閥X刑警第二季</a></h2>
    <p class="search-item__meta"> 韓劇 · 2026 · 韓國 · 更新至10 </p>
```

現行 `GimyTvSource.search()` / `GimyMaxSource.search()` 呼叫 `parser.parseVodList()`
（找 `a.card__thumb`），即使 CF 解開也會回 0 筆。

新增 `GimyParser.parseSearchResults()`：以 `a.search-item__thumb` 為錨點，
標題取 `aria-label`（缺則 `h2.search-item__title`），狀態自 `p.search-item__meta`
末段解析（格式 `類型 · 年份 · 地區 · 狀態`）。

`gimytv.me` 與 `gitube.tv` 的搜尋版型完全相同，僅 href 為 `/vod/` vs `/title/` ——
沿用第一輪的 `GimyPaths.detail`，不需新增設定項。

---

## 4. 變更清單

| 檔案 | 變更 |
|---|---|
| `data/network/CloudflareGateway.kt` | 新增：challenge 偵測、WebView 解題、per-host Mutex |
| `data/network/CfCookieStore.kt` | 新增：記憶體 CookieJar + DataStore 持久化 |
| `data/network/WebViewUserAgent.kt` | 新增：取得 WebView 真實 UA，含缺席降級 |
| `di/NetworkModule.kt` | 接上 CookieJar 與 gateway interceptor；UA 改注入 |
| `data/scraper/EmbeddedHlsSource.kt` | `userAgent` 由 `private` 放寬為 `protected open`，預設值不變 |
| `data/scraper/JableTvSource.kt` | 覆寫 `userAgent` 為 WebView UA（XNXX 為同父類子類，不得直接改父類） |
| `data/scraper/parser/GimyParser.kt` | 新增 `parseSearchResults()` |
| `data/scraper/GimyTvSource.kt`、`GimyMaxSource.kt` | `search()` 改呼叫 `parseSearchResults()` |

---

## 5. 測試策略

沿用專案慣例（inline HTML fixture + Jsoup + Truth）。fixture 取自 2026-09-10 實抓頁面。

| 測試 | 驗證意圖 |
|---|---|
| `parses gimytv search results` | `search-item` 版型能取出 id / 標題 / 封面 |
| `parses gitube search results with title paths` | 同一 parser 換 paths 後可解析 `/title/` |
| `search paths gate the match` | 一鏡像的設定不應解析另一鏡像的搜尋 HTML |
| `extracts status from search meta` | 從 `類型 · 年份 · 地區 · 更新至10` 取出狀態 |
| `challenge detection requires both 403 and marker` | 403 但無 challenge 標記 → 不觸發 WebView（避免把權限錯誤誤判） |
| `challenge marker without 403 is not a challenge` | 反向：200 頁面內含 `cf_chl_opt` 字樣不應誤判 |
| `retries once and no more` | 重試後仍 403 → 放棄，不無限循環 |
| `gateway passes through when webview unavailable` | 降級路徑回傳原始 403，不拋例外 |
| `xnxx keeps its original user agent` | Jable 覆寫 UA 不得波及同父類的 XNXX —— 這是本輪最容易誤傷的相鄰功能 |

challenge 偵測、重試上限、降級皆為純邏輯，抽成不依賴 Android framework 的函式後
可在 JVM 單元測試涵蓋（專案無 Robolectric）。WebView 解題本身需實機驗證。

---

## 6. 風險與限制

1. **Cloudflare 可能升級為互動式驗證碼**。屆時 WebView 自動解題失效，
   需改為顯示可見 WebView 讓使用者手動完成 —— 本輪不實作，但 gateway 介面預留
   「解題失敗」路徑，不致崩潰。
2. **`cf_clearance` 綁 IP**。TV 通常固定於家用網路，換網路（如接手機熱點）會失效，
   由「再次 403 觸發重解」自然處理。
3. **WebView 版本過舊**可能無法通過 challenge。降級路徑同 3.4。
4. **本方案僅繞過機器人驗證，不繞過任何付費牆或存取控制** —— 這些站台的內容本身
   對匿名訪客公開，challenge 阻擋的是自動化流量而非授權。

---

## 7. 驗收標準

1. `./gradlew test` 全綠，含上述新增測試。
2. 實機：搜尋一個關鍵字，首次等待後回傳非空結果，且結果含 GimyMax（GMX）標籤。
3. 實機：搜尋第二次立即回應（cookie 已快取）。
4. 實機：關閉 App 重開後搜尋仍立即回應（DataStore 持久化生效）。
5. 實機：Jable 來源可瀏覽列表並播放。
6. 未受影響的來源（MacCms 系、5278、XNXX、MovieFFM）行為不變。
