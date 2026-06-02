# Scraper 失效修復 + 解析感知健康探測機制

**日期**: 2026-06-02
**狀態**: 設計待審
**背景**: gimytv / gimymax 換網域後發現整批站點改版,現有 scraper 全面失效。

---

## 1. 問題陳述

Gimy 系列站點更換網域並同步改版 HTML 模板,導致現有 scraper 的 CSS 選擇器
0 命中,App 顯示空白且**無任何錯誤**(靜默失效)。根因有二:

1. **選擇器寫死且模板已變** — 列表/詳情選擇器對應舊模板,新模板完全不同。
2. **健康探測只看 HTTP 200** — `EndpointResolver.pickBestEndpoint` 用 HEAD 請求,
   每個改版站都回 200,探測無法區分「健康」與「200 但解析爛掉」。

### 爆炸半徑(2026-06-02 實測)

| 來源 | 網域變化 | 狀態 | 破壞點 |
|---|---|---|---|
| gimytv | gimytv.ai → **gimyplus.com** | 全壞 | 列表 + 詳情模板全換 |
| gimymax | gimy01.tv → **gimy01.co** | 全壞 | 列表 + 詳情模板全換,集數路徑 `/ep/`→`/eps/` |
| eyny_tv | eynytv.com(未變) | 全壞 | 改 dianyingim/module 模板 |
| gimy_tw | gimy.tw(未變) | 退化可用 | 詳情 `data-toggle=tab` 線路分頁消失,線路名丟失 |
| imaple / momovod / kubo123 | 未變 | 正常 | — |

**播放頁取流方式三站皆未變**(gimyplus=`player_data`、gimy01=`player_data`/`player_aaaa`、
eynytv=`player_aaaa`)→ 取流邏輯可完全重用,只需重寫列表 + 詳情選擇器。

---

## 2. 範圍

**做**:
1. 重寫 gimytv、gimymax、eyny_tv 三個全壞 scraper 的列表 + 詳情解析。
2. 修 gimy_tw 線路名退化。
3. 機制:`EndpointResolver` 探測升級為「parser-based 健康驗證 + 自動選可解析的候選網址」。
4. 更新 `endpoints.json` 三個新網域。

**不做**(YAGNI / 已評估排除):
- 選擇器 config 化(搬進遠端 JSON)。理由:本次破壞多為結構性(新路徑、新 player 變數、
  集數結構),選擇器 DSL 表達不了,仍須改 code 發版;DSL 維護成本不划算。
- 動 imaple / momovod / kubo123(實測列表正常,不在問題範圍)。

---

## 3. Part A — Scraper 重寫(新模板對照)

### 3.1 GimyTvSource(gimyplus.com)

| 環節 | 舊選擇器 | 新選擇器 |
|---|---|---|
| 列表卡片 | `a[class*=video-pic][data-original]` | `a.card__thumb[href^=/vod/]` |
| 封面 | `data-original` 屬性 | 卡片內 `img[src]`(直給,非 lazy) |
| 標題 | `title` 屬性 | `aria-label`,fallback `img[alt]` / 同卡 `a.card__body h3.card__title` |
| 狀態 | `span.note` | `span.card__badge` |
| 詳情線路容器 | `div.playlist-mobile.playlist` | `div.playlist-block` |
| 線路名 | `div.gico` | `.playlist-block__title`(去除 ` ᴴᴰ`) |
| 集數連結 | `ul#con_playlist_ a[href~=/ep/]` | `.playlist-grid a[href~=/ep/]` |
| 集數路徑/regex | `/ep/{vod}-{src}-{ep}.html` | 不變 |
| 取流 | `player_data` 括號計數 | **不變,重用 `parsePlayerData`** |

線路名 → sourceId 來自集數連結中段數字(例:高清線路=1、優酷線路=8),
與舊版一致,排序用既有 `stabilityOrder`。

### 3.2 GimyMaxSource(gimy01.co)

| 環節 | 舊選擇器 | 新選擇器 |
|---|---|---|
| 列表卡片 | `a[class*=video-pic][data-original]` | `a.poster[href^=/vod/]` |
| 封面 | `data-original` | `span.poster__thumb img[src]`(CDN 改 `cdn.picsu.pics`) |
| 標題 | `title` 屬性 | `h3.poster__title` |
| 狀態 | `span.note` | `span.poster__status` |
| 詳情線路名 | `div.gico` | `.sources .source` chips(高清線路ᴴᴰ / 4K畫質線路ᴴᴰ / 如意雲…) |
| 集數容器 | `ul#con_playlist_` | `div.block`(內 `div#{sourceId}`) |
| **集數路徑** | `/ep/{vod}-{src}-{ep}.html` | **`/eps/{vod}-{src}-{ep}.html`** ← 路徑變更 |
| 取流 | `player_data` | **不變,重用 `parsePlayerData`**(頁面同時含 `player_aaaa` 備援) |

⚠️ `fetchPlayerData` 的 episodeUrl 已是頁面提供的相對路徑 `/eps/...`,
路徑變更由詳情頁解析自然帶入,但 regex `/ep/` 需改為 `/eps/`。

### 3.3 EynyTvSource(eynytv.com,MacCmsListBasedSource 子類)

eyny 已脫離 myui 模板,改用 dianyingim/module 模板,且**圖片在 `<a>` 外的兄弟節點**
(`.module-item` 內 `a[href=/voddetail]` 與 `img.lazy[data-src]` 平行),
基底類別「以 `<a>` 為中心、img 在同節點」的迴圈不適用。

決策:**EynyTvSource override 列表 + 集數解析**,不污染基底(基底仍服務
myui 的 imaple/momovod/kubo123):

| 環節 | 新選擇器 |
|---|---|
| 列表卡片容器 | `.module-item` |
| 連結 | `a[href^=/voddetail/][title]` |
| 封面 | `img.lazy[data-src]`(lazy,讀 `data-src`) |
| 詳情線路 tab | `.module-tab.module-player-tab .module-tab-item` |
| 集數清單 | `.module-list.module-player-list a[href~=/vodplay/]` |
| 集數路徑 | `/vodplay/{vod}-{src}-{ep}.html`(不變) |
| 取流 | **不變,重用基底 `parsePlayerAaaa`** |

### 3.4 gimy_tw 線路名退化修復(MacCmsListBasedSource)

gimy.tw 列表靠基底 `video-pic[data-original]` fallback 仍命中,播放頁 `player_aaaa` 正常,
僅詳情頁 `a[data-toggle=tab][href^=#playlist]` 消失 → 落入 fallback 桶,線路名變成「線路 N」。

實測 gimy.tw 詳情頁線路結構為 `div.playlist-mobile.playlist` + `div.gico`(線路名,
如「…雲」,gico class)+ `ul[id^=con_playlist_]` 內 `/vodplay/` 連結——即舊版 gimymax/gimytv
那套結構,而非基底預期的 `data-toggle=tab`。

決策:在基底 `parseEpisodeGroups` 增加 `div.playlist-mobile.playlist` + `div.gico` 分支
(取線路名,集數從同容器 `a[href*=/vodplay/]`),置於 `data-toggle=tab` 分支之後、
桶式 fallback 之前。保留現有兩條路徑。此為退化修復,優先序低於三個全壞站。

---

## 4. Part B — 解析感知健康探測機制(方案 A)

### 4.1 核心構想

**用真正的 parser 當健康預言機**:探測候選網址時,不再用 HEAD 看 200,
而是實際抓該站列表頁、用該站 parser 解析,**解析出 ≥ 門檻筆數才算健康**。
這一招同時達成:① 補掉「200 但壞掉」盲點 ② 候選網址中自動選「真的能解析」的那個
③ 配合 log 讓靜默失效變可見。

### 4.2 介面變更

`SiteSource` 新增健康探測方法(可帶預設實作):

```kotlin
interface SiteSource {
    // 既有...
    /** 對指定 baseUrl 抓預設分類列表頁並解析,回傳解析出的筆數。
     *  探測用,與 getBaseUrl 解耦(不可走 endpointResolver.getBaseUrl)。 */
    suspend fun probeListCount(baseUrl: String): Int
}
```

每個 source 提供:預設探測分類(如電視劇 typeId=2)+ 用既有解析函式對
**傳入的 baseUrl** 跑一次列表解析。需把列表 URL 組裝抽成吃 `baseUrl` 參數的形式
(現有多數 source 的 `parseVodList` 已是純函式,只需參數化 URL 組裝)。

### 4.3 EndpointResolver 改動

- `pickBestEndpoint` 由「HEAD 200」改為「`probeListCount(url) >= MIN_HEALTHY_ITEMS`」,
  在成功的候選中仍依優先序取第一個。全部候選都不健康時,fallback 到原本邏輯
  (HEAD 200 或 first candidate),避免完全無法播。
- **打破 DI 循環**:SiteSource 依賴 EndpointResolver,故 EndpointResolver 反向取得
  sources 須用 `Provider<Map<SourceType, @JvmSuppressWildcards SiteSource>>`(Hilt 多重綁定 + 延遲取得)。
- `MIN_HEALTHY_ITEMS` 常數(建議 5;列表頁正常回 12+ 筆,改版/壞站回 0)。
- 探測成本:GET + parse 取代 HEAD,僅在 24h 過期 / 下拉刷新時跑,8 來源平行,可接受。

### 4.4 可見性(輕量告警)

- 探測結果寫 log(每來源:解析筆數 / 採用網址)。
- (選配,實作時評估)設定頁顯示各來源健康狀態,讓維護者快速得知哪站壞了。

---

## 5. 測試策略

- **Scraper 解析**:用實測抓下的 HTML 樣本(gimyplus / gimy01 / eynytv 列表 + 詳情)
  存為測試 fixture,對新解析函式斷言:列表筆數 > 0、首筆 id/title/cover/status 正確、
  詳情線路數 > 0 且線路名非「線路 N」、集數路徑格式正確。
  測試需編碼「為何」:例如「gimymax 集數路徑必須是 /eps/」直接斷言 regex。
- **健康探測**:對「正常列表 HTML」與「改版空殼 HTML」兩種 fixture 斷言
  `probeListCount` 分別 ≥MIN 與 0;`pickBestEndpoint` 在「首選壞、次選好」時選次選。
- 既有 `EpisodeNormalizer` / `EpisodeStatusParser` 測試不受影響。

---

## 6. 風險與緩解

| 風險 | 緩解 |
|---|---|
| HTML fixture 會過期,測試變成驗證舊模板 | fixture 註明抓取日期;探測機制本身會在 runtime 偵測真實失效 |
| 探測成本上升拖慢冷啟動 | 僅刷新時跑、平行化、3s timeout 不變 |
| 全部候選都不健康時無站可用 | fallback 保留原行為,至少不比現況差 |
| DI 循環 | Provider 延遲注入,已驗證可行 |
| 同營運商所有鏡像同步改版 → 自動換 URL 無效 | 接受此限制;此情境仍需發版,但機制讓它「可偵測」而非靜默 |
