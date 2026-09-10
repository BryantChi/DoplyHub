# Endpoint 健康稽核報告

**日期**: 2026-09-10
**觸發**: 例行確認 `endpoints.json` 是否有失效網址
**結論**: 11 個站台 endpoint 中 3 個失效（GIMYTV、GIMYMAX、JABLE_TV），
另發現 Gimy 全家族搜尋路徑遭 Cloudflare 攔截、以及一個容易誤判為站台失效的本機 DNS 攔截。

---

## 1. 稽核方法

分兩層，與 `EndpointResolver` 自身的健康判準一致：

1. **連通層** — HTTP 狀態碼、重導向鏈、DNS 解析。
   UA 使用 App 實際值 `Mozilla/5.0 (Linux; Android 10) Mobile Safari/537.36`。
2. **解析層** — 打各 `SiteSource.probeListCount()` 實際使用的列表頁，
   依對應 parser 的 CSS 選擇器計數。

第 2 層是關鍵：`MIN_HEALTHY_ITEMS = 5`，**HTTP 200 但解析不出項目一樣算失效**。
只驗第 1 層會漏掉本次三個失效站中的兩個。

---

## 2. 總覽

| Source | endpoint | HTTP | 解析項目 | 判定 |
|---|---|---|---|---|
| GIMYMAX | gimy01.co | 301 → gitube.tv | **0** | ❌ 失效 |
| GIMYTV | gimyplus.com | 200（導回首頁） | **0** | ❌ 失效 |
| JABLE_TV | jable.tv | **403** | **0** | ❌ 失效 |
| MOVIEFFM | www.movieffm.net | 200 | 42 / 搜尋 40 | ✅ 正常 |
| GIMY_TW | gimy.tw | 200 | 192 | ✅ |
| EYNY_TV | eynytv.com | 200 | 128 | ✅ |
| KUBO123 | 123kubo.net | 200 | 154 | ✅ |
| IMAPLE_TV | imaple.tv | 200 | 48 | ✅ |
| MOMOVOD | momovod.app | 200 | 48 | ✅ |
| XNXX | www.xnxx.com | 200 | 36 | ✅ |
| FORUM5278 | 5278.cc | 200 | 100（forum-23 / 42） | ✅ |
| endpoints.json | raw.githubusercontent.com | 200 | — | ✅ |
| 更新檢查 | api.github.com | 200 | — | ✅ |
| 外部播放器 | player.hboav.com | 200 | — | ✅ |

---

## 3. 失效詳情

### 3.1 GIMYMAX — gimy01.co 改版並搬遷至 gitube.tv

`gimy01.co` 回 301 至 `gitube.tv`。OkHttp `followRedirects(true)` 抓得到頁面，
但抓回來的 HTML 結構與 `GimyMaxParser` 期待的完全不同：

```
gitube.tv/browse/2.html 的 a 標籤 class 統計
 162 class="card__thumb"     ← 新版型
 162 class="card__body"
   0 class="poster"          ← parser 找的，已消失
```

| 項目 | GimyMaxParser 期待 | gitube.tv 實際 |
|---|---|---|
| 列表路徑 | `/type/{id}.html` | `/browse/{id}.html` |
| 詳情路徑 | `/vod/{id}.html` | `/title/{id}.html` |
| 劇集路徑 | `/eps/{vod}-{sid}-{ep}.html` | `/watch/{vod}-{sid}-{ep}.html` |
| 列表卡片 | `a.poster` | `a.card__thumb` |
| 詳情線路 | `.sources .source[data-id]` + `div.block div[id]` | `div.playlist-block` + `.playlist-block__title` |
| player 變數 | `player_data={...}` | `var player_aaaa={...}` |

舊路徑仍有 301（`gimy01.co/vod/465222.html` → `gitube.tv/title/465222.html`），
所以「抓得到頁面」，只是抓到的東西 parser 讀不懂 —— 這正是靜默失效的典型形態。

**player 解析的實際故障**：`gitube.tv` 播放頁同時出現
`var player_aaaa={...}`（位置 11443）與 `player_data=player_aaaa;`（位置 11943）。
現行 `parsePlayerData` 以 `indexOf("player_data=")` 定位後找下一個 `{`，
會落在位置 33740 的一段 JS：

```
{try{if(!((window.matchMedia&&window.matchMedia("(pointer:coarse)")…
```

`JSONObject(...)` 對此拋 JSONException，播放直接失敗。

### 3.2 GIMYTV — gimyplus.com 已轉型為線路索引工具

站台性質改變，不再是影視站。首頁僅 8.6 KB，自述：

> 干净的视频线路索引：免费、无广告、不用登录
> Gimy Plus 是一个视频线路索引工具。本站不存储、不上传或分发任何视频文件

- 分類列表 `/type/{id}.html` → 302 導回首頁，站上不存在 `/vod/` 詳情頁
- 搜尋 `/search/{kw}----------{p}---.html` → 403 Cloudflare Managed Challenge
  （試過首頁自身的 13-dash 格式、加 `Referer`，皆 403）

`GimyTvSource` 的三個入口（列表、詳情、搜尋）全數失效。

### 3.3 JABLE_TV — 全站 Cloudflare Managed Challenge

`jable.tv/hot/` 回 403，內容為 `Just a moment...` + `_cf_chl_opt`。
改用桌面 Chrome UA 並補上 `sec-ch-ua` / `sec-fetch-mode` / `sec-fetch-dest` /
`upgrade-insecure-requests` 後仍為 403 —— 需執行 JS 才能通過，純 OkHttp 無解。
`EmbeddedHlsSource` 目前只設 UA 與 Referer，無任何繞過機制。

---

## 4. Gimy 家族網域全貌

### 4.1 網域血脈（由 git 歷史回溯 + 實測轉址）

```
GIMYTV  血脈：gimytv.ai → 301 → gimytv.me     （gimyplus.com 是岔路，已轉型）
GIMYMAX 血脈：gimy01.tv → 301 → gitube.tv
```

兩條血脈各自延續，未合流。這決定了既有收藏的存活狀況：

- 舊 GIMYTV 收藏：id 屬 `gimytv.ai` 空間 = `gimytv.me` 空間 → **相容，可保留**
- 舊 GIMYMAX 收藏：id 屬 `gimy01.tv/.co` 空間 = `gitube.tv` 空間 → **相容，可保留**
- 但兩條血脈之間 **id 不可互換**（見 4.3）

### 4.2 各網域結構對照

| | gimytv.me | gimyai.tw | gitube.tv | gimytv.tw | gimy.tw |
|---|---|---|---|---|---|
| 版型 | 新版 A | 新版 B | 新版 A | MacCms | MacCms |
| 列表 | `/type/` | `/genre/` | `/browse/` | `/type/` | `/vodtype/` |
| 詳情 | `/vod/` | `/detail/` | `/title/` | `/vod/` | `/voddetail/` |
| 劇集 | `/ep/` | `/play/` | `/watch/` | — | — |
| 搜尋 | `/search/` | `/find/` | `/search/` | — | — |
| 列表卡片 | `a.card__thumb` | `a.poster` | `a.card__thumb` | `a.myui-vodlist__thumb` | 同左 |
| 詳情線路 | `div.playlist-block`<br>`.playlist-block__title` | `div.block`<br>`.route-title`<br>`[data-route-sid]` | `div.playlist-block`<br>`.playlist-block__title` | — | — |
| player 變數 | `player_data` | `player_data` | `player_aaaa` | — | — |
| 實測項目數 | 198 | 252 | 162 | 234 | 192 |

`gimytv.me` 與 `gitube.tv` 的列表卡片 HTML 逐字相同，僅 href 路徑不同：

```html
<!-- gitube.tv -->
<article class="card card--c3">
  <a href="/title/464387.html" class="card__thumb" aria-label="早春晴朗">
    <img src="…"><span class="card__badge">全24集</span></a>
  <a href="/title/464387.html" class="card__body"><h3 class="card__title">早春晴朗</h3></a>

<!-- gimytv.me -->
<article class="card card--c3">
  <a href="/vod/483139.html" class="card__thumb" aria-label="早春晴朗">
    <img src="…"><span class="card__badge">24集全</span></a>
  <a href="/vod/483139.html" class="card__body"><h3 class="card__title">早春晴朗</h3></a>
```

**現行 `GimyTvSource` + `GimyTvParser` 與 `gimytv.me` 100% 相容**
（列表 198 筆、詳情 `h1` + `playlist-block` + `/ep/`、播放 `player_data={...}` 均驗證通過）。

### 4.3 id 空間相容性 —— 多網域備援的硬約束

同一 id 打三個網域的實測結果：

| id | gimytv.me | gimyai.tw | gitube.tv |
|---|---|---|---|
| 425737 | 那些你不知道的我 | **那些你不知道的我** | 七大罪：天空的囚人 |
| 431986 | 向流星許願的我們 | **向流星許願的我們** | 晚風棲野 |
| 432208 | 都市開基祖 | **都市開基祖** | 404 |

- `gimytv.me` ↔ `gimyai.tw`：列表 id 交集 185/189，同 id 同片 → **真鏡像，可互為備援**
- `gitube.tv`：id 範圍與前兩者重疊（417k–486k）但**內容不同**
  → 放進同一候選清單會讓收藏默默指向錯誤影片，**不可互換**
- `gimytv.tw`（84–290636）、`gimy.tw`（82925–322466）：id 空間各自獨立

`gitube.tv` 並非廢站：與 `gimytv.me` 同分類片單比對，片名交集 118/152（78%），
`gitube.tv` 獨有 34 部（「Oasis 綠洲謎蹤」「佔領電視臺」等）。

### 4.4 排除的網域

| 網域 | 狀態 |
|---|---|
| gimy.tv / gimy.co / gimy.im / gimy.ai / gimy.app / gitube.cc | DNS 不存在 |
| gimytv.com / gimytv.cc | 200 但僅 114 bytes，空殼 |
| gimy.me | 403 |
| gimy.cc / gimyvod.com | JS 轉址跳板，非穩定入口 |
| gimy.bot / gimy.tube / play.gimy.tube / v.gimy.bot | 播放器子網域，非站台入口 |
| gimymax.com | 公告頁（2026-05 起），已排除於 DEFAULTS |

各站頁面內皆無「備用網址」公告可供自動探索。

---

## 5. Cloudflare 攔截範圍

| 目標 | 狀態 |
|---|---|
| `gimytv.me/search/…` | 403 Managed Challenge |
| `gimyai.tw/find/…` | 403 Managed Challenge |
| `gitube.tv/search/…` | 403 Managed Challenge |
| `gimyplus.com/search/…` | 403 Managed Challenge |
| `jable.tv/*` | 403 Managed Challenge（全站） |
| 上述各站列表 / 詳情 / 播放頁 | 200 正常 |

Gimy 家族僅在搜尋路徑掛 challenge，其餘路徑不受影響。
**Jable 全站受影響**，與 Gimy 搜尋是同一個技術問題，可一併處理。

---

## 6. 假失效：MOVIEFFM 的本機 DNS 攔截

第一輪 curl 對 `www.movieffm.net` 直接噴 SSL 錯誤，追查後為 DNS 層攔截：

```
系統 DNS：  www.movieffm.net → 182.173.0.181     ← 攔截伺服器
8.8.8.8：   www.movieffm.net → 104.21.45.231     ← 真實 Cloudflare IP
1.1.1.1：   www.movieffm.net → 104.21.45.231

憑證：subject=CN=rpz10-landing  issuer=CN=rpz10-landing（自簽）
```

`rpz` = Response Policy Zone，DNS 層封鎖機制，通常來自 ISP 或路由器的過濾服務。
以 `--resolve` 指向真實 IP 後，站台完全正常（列表 42 筆、搜尋 40 筆）。

**對 App 的影響**：`NetworkModule` 未設自訂 TrustManager，OkHttp 會驗憑證，
因此在同一條網路下的 Android 裝置抓 movieffm 會直接 `SSLHandshakeException`。
這不是 endpoint 失效，換 DNS（8.8.8.8 / 1.1.1.1）即可。

**這也是「探測失敗即自動刪除收藏」不可行的直接證據** ——
若該邏輯已上線，在這條網路開一次 App，MovieFFM 的收藏與觀看紀錄會全數消失，
而原因只是路由器上的 DNS 過濾。

---

## 7. 機制缺陷：健康探測偵測得到，卻救不回來

`EndpointResolver.refresh()` 的 fallback 鏈：

```kotlin
val healthy = if (source != null)
    pickHealthiest(candidates, MIN_HEALTHY_ITEMS) { url -> source.probeListCount(url) }
else null
healthy ?: pickBestEndpoint(candidates) ?: candidates.first()
```

`pickHealthiest` 正確判定 `gimy01.co` / `gimyplus.com` 不健康（回 0 < 5）並回傳 `null`，
但接著 fallback 到 `pickBestEndpoint`（HEAD 探測）—— 這兩個網域 HEAD 都回 200
（一個 301 到活著的新站，一個首頁還在），於是**最終仍選中壞掉的 URL，且無任何告警**。

根因是 `endpoints.json` 每個 source 只有一個候選網址。
健康探測的價值在於「多個候選裡挑活的」，只有一個候選時，探測結果無處可用。

---

## 8. 後續處置

分四輪，各自獨立驗收：

| 輪次 | 範圍 | 狀態 |
|---|---|---|
| 1 | GIMYTV → `gimytv.me`；GIMYMAX → `gitube.tv`（路徑 token 參數化，共用 parser） | 設計完成，見 specs |
| 2 | `endpoints.json` schema 擴充帶站點 profile；`EndpointResolver` 支援多網域候選；健康狀態可見性（設定頁 + 列表失效提示） | 待設計 |
| 3 | 收藏 / 觀看紀錄失效標記、一鍵清除、嚴格條件自動移除（含 Room migration） | 待設計 |
| 4 | Cloudflare 繞過（同時解決 Jable 全站與 Gimy 搜尋） | 待設計 |
