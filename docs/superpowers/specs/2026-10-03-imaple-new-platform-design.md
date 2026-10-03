# 新平台來源重寫：eynytv / imaple / 123kubo 合併為 Imaple

**日期**: 2026-10-03
**狀態**: 設計待審
**背景**: 2026-10-03 全來源稽核發現 eynytv、imaple、123kubo 三站同日改版，App 現行的
MacCMS 爬法從列表開始全部失效。稽核紀錄見 auto memory `discovery_site_structure.md`
的「2026-10-03 全來源稽核」一節。

---

## 1. 問題陳述

三站換成**同一套**新平台（Next.js App Router，頁面含 `/_next/`）：

| 舊路徑 | 現況 |
|---|---|
| `/vodtype/<id>.html` | 301 到 `/series`、`/movie`、`/anime`… |
| `/voddetail/<id>.html`、`/vod/<id>.html` | **410 Gone**，無對照表 |
| `/vodplay/…`、`/play/<id>-<sid>-<n>.html` | 410 |
| `/vodsearch/…?wd=` | 301 到 `/search?wd=` |

實測三站**是同一個資料庫**：`/movie?pg=3` 在三站回傳相同 28 部、順序與 cuid 完全一致，
總數相同（7,453）。App 目前把它們當成三個獨立來源，修好後若照舊保留，搜尋每部片會出現
三次、首頁三列內容重疊。

---

## 2. 已確認的決定

| 項目 | 決定 | 理由 |
|---|---|---|
| 三站處理 | **合併成一個來源**，三個網域互為備援 | 內容完全相同，保留三個只會造成重複 |
| 保留身分 | **Imaple（`SourceType.IMAPLE_TV`，標籤 IMP）** | 原本加入它就是看上動漫片庫；品牌最廣為人知 |
| 網域順序 | imaple.tv → eynytv.com → 123kubo.net | 沿用 EndpointResolver 的優先序機制 |
| EYNY_TV、KUBO123 | **enum 保留**，不再註冊、不顯示 | 收藏與紀錄存的是型別名稱字串，刪 enum 會讓讀舊資料時崩潰 |
| 解析方式 | **混合式**：列表與搜尋解析卡片 HTML；詳情、線路、取流解析 RSC 內嵌 JSON | 線路、集數、m3u8 只存在 RSC 裡；站方只改其中一種格式時，另一部分不受影響 |
| 首頁 | 原本三列（動漫、韓劇、電影）**全部改由 Imaple 提供** | 首頁排版不變 |
| 舊收藏／紀錄（舊數字 id） | **沿用既有 `SavedEntryRecovery`，以片名自動找回**；找不到才顯示既有的 `detail_vod_gone` 訊息並標為失效 | 舊 id 已 410 且無對照表，但這個機制正是 v3.1.0 換網域時為此而建；新來源只要在找不到 cuid 時丟 `ScraperException`（不是 `IOException`）就會自動觸發，不必寫新程式 |
| 既有的 30 天清理問題 | **一併修正** `embed_slugs` 與 `movieffm_slugs` | 新來源會沿用 `embed_slugs`，不修就會繼承同一個問題 |
| 清理 SQL 的測試 | JVM 上用 sqlite-jdbc 執行與 DAO 相同的 SQL | 專案沒有 Room／Robolectric 測試設定，這是最輕的可驗證做法 |

---

## 3. 範圍

**做**：
1. 新增新平台 parser（純解析、可在 JVM 測試）。
2. 改寫 `ImapleTvSource` 接新平台，cuid ⇄ `Long` 對照沿用 `embed_slugs`。
3. 三站合併：取消註冊 EYNY_TV、KUBO123，首頁三列改由 Imaple 提供，設定頁不再列出這兩個來源。
4. `EndpointResolver.DEFAULTS` 與 `endpoints.json` 的 imaple_tv 改為三網域候選。
5. 修正 `embed_slugs`、`movieffm_slugs` 的 30 天清理會刪掉收藏對照的問題。
6. 刪除只屬於這三站的舊 MacCMS 程式碼（刪除前確認沒有其他引用）。

**不做**：
- 為舊數字 id 另寫對應邏輯：既有 `SavedEntryRecovery`（`DetailViewModel` 載入失敗時自動呼叫）已會以片名找回。
- `needsProxy` 為真的線路（本次樣本皆為假，行為未驗證）：先過濾掉，不處理。
- 修改 `SiteSource` 介面。
- `sort` 參數的其他排序方式（目前只確認預設值 `latest`）。

---

## 4. 新平台結構（2026-10-03 實測）

| 資料 | 位置 | 備註 |
|---|---|---|
| 列表卡片 | `a.navigation-link.stub[href^=/title/]` | 片名 `h3.stub-title[title]`、更新狀態 `p.stub-meta`、封面 `img[src^=/pi/]`（站內代理路徑，需補網域） |
| 列表分頁 | `?pg=N`，每頁 28 筆 | 總數在「共 `<span>N</span>` 部」 |
| 篩選 | `area`（cn、tw、hk、kr、jp、west、th、other）、`tag`、`year`、`lang` | 可用值列在 RSC 的 `facets` |
| 搜尋 | `/search?wd=…&pg=N`，伺服器端渲染，卡片同列表 | **0 筆時頁面仍有「你可能會喜歡」卡片**，必須先讀「找到 N 筆」 |
| 詳情 | `/title/<cuid>` 的 RSC：`title{id,name,pic,remarks}`、`lines[{from,sourceKey,sourceLabel,knownDead,needsProxy,episodes[{name}]}]` | 年份、類型、簡介在同頁的 JSON-LD 與 meta description |
| 集數網址 | `/play/<cuid>/<序號>?line=<sourceKey>.<from 中「·」後的代碼>` | 序號從 1 起，依位置 |
| 取流 | 播放頁 RSC：`detail.playGroups[].episodes[].url` | **只有網址參數指定的那條線路會帶全部集數的 url**，其他線路只帶鄰近集數 |
| id | cuid 字串（例 `cmush6vfu0193qoj6vdva66t2`） | 三站相同；無數字 id |
| 反爬 | 站在 Cloudflare 後，未遇到挑戰頁；連續 25 次請求皆 200 | 若出現挑戰，由既有 `CloudflareInterceptor` 處理 |

---

## 5. 架構與元件

```
ImapleTvSource（改寫，沿用 SourceType.IMAPLE_TV）
 ├─ 網路：OkHttp 抓頁面，網域由 EndpointResolver 挑選
 ├─ id 對照：cuid ⇄ Long（雜湊），記憶體 LRU + embed_slugs 表
 ├─ 分類：typeId ⇄ 新平台路徑與篩選參數（集中在一張對照表）
 └─ 呼叫 ↓
新平台 parser（新檔，只做解析、不碰網路）
 ├─ parseCards(doc)        列表與搜尋的卡片
 ├─ parseTotal(doc)        「共 N 部」「找到 N 筆」→ 總數、是否有下一頁
 ├─ extractRsc(html)       串接所有 self.__next_f.push 片段
 ├─ parseLines(rsc)        線路與集數（濾掉 knownDead、needsProxy）
 └─ parseStreamUrl(rsc, line, n)  指定線路、指定集數的 m3u8
```

- **解析與網路分開**：parser 只吃字串或 Document，可用真實頁面在 JVM 測試，同 `GimyParser` 的做法。
- **各函式各自防禦**：任一部分解析失敗，不影響其他部分。
- **cuid 對照沿用 `EmbeddedHlsSource` 的模式**：雜湊函式、`resolveSlug()` 的「記憶體 → 資料庫」查找、列表頁寫入資料庫。

**分類對應**（`SiteCategoryMap` 的 IMAPLE_TV 項目改寫；typeId 是 App 內部代碼，只在 Imaple 內有意義）：

| 標準分類 | 新平台路徑 |
|---|---|
| MOVIE | `/movie` |
| SERIES | `/series` |
| ANIME | `/anime` |
| VARIETY | `/variety` |
| KOREAN | `/series?area=kr` |
| CHINESE | `/series?area=cn` |
| HK | `/series?area=hk` |
| TAIWAN | `/series?area=tw` |
| JAPANESE | `/series?area=jp` |
| AMERICAN | `/series?area=west` |
| DOCUMENTARY | `/doc` |
| ADULT | 不提供 |

**合併涉及的位置**：

| 位置 | 改動 |
|---|---|
| `RepositoryModule` | 取消 EYNY_TV、KUBO123 的綁定 |
| `VodRepositoryImpl` 的來源分派 | EYNY_TV、KUBO123 改導向 Imaple：`findByTitle` 會先搜「原來源」，導向後舊的 Eyny／Kubo 收藏會直接在 Imaple 以片名找回並改指為 IMAPLE_TV |
| `HomeScreen.MORE_SOURCES` | 三列都改為 IMAPLE_TV（動漫、韓劇、電影）；實作時確認同一來源出現多列不會造成 LazyColumn key 重複 |
| 設定頁來源管理 | 不列出 EYNY_TV、KUBO123 |
| `EndpointResolver` | DEFAULTS 的 IMAPLE_TV 改三網域；探測時略過未註冊的來源型別，避免白白打已停用的網站 |
| `endpoints.json` | imaple_tv 改三網域候選；eyny_tv、kubo123 項目保留（舊版 App 仍會讀，移除或保留對它們都一樣是壞的，保留比較不意外） |

---

## 6. 資料流

| 操作 | 請求 | 解析 |
|---|---|---|
| 列表 `fetchVodList(typeId, page)` | 對照表 → 路徑 + `pg` | 卡片 HTML；「共 N 部」算是否有下一頁；看到的 cuid 寫入 `embed_slugs` |
| 詳情 `fetchVodDetail(vodId)` | `vodId` → cuid（記憶體 → 資料庫）→ `/title/<cuid>` | RSC `title`、`lines`；同頁 JSON-LD 與 meta 的年份、類型、簡介 |
| 集數 | 由 `lines` 組出 `/play/<cuid>/<序號>?line=…` | 一條線路 = 一個 `EpisodeGroup` |
| 取流 `fetchPlayerData(episodeUrl)` | 該集播放頁 | RSC `detail.playGroups` 中該線路、該集的 `url` |
| 搜尋 `search(kw, page)` | `/search?wd=…&pg=…` | 先讀「找到 N 筆」；N = 0 直接回空；否則解析卡片並寫入 `embed_slugs` |
| 健康探測 `probeListCount` | 候選網域的 `/series` | 以新 parser 計數，沿用 ≥ 5 筆為健康的門檻 |

---

## 7. 錯誤處理

| 情況 | 處理 | 目的 |
|---|---|---|
| 「共 N 部」N > 0，卻解析出 0 張卡片 | 丟 `ScraperException`（版型變更） | 讓健康偵測標成「失效」，而不是安靜顯示空白 |
| 搜尋「找到 0 筆」 | 回空結果 | 不把推薦卡片當成搜尋結果 |
| 詳情頁沒有任何可用線路 | 丟 `ScraperException` | 詳情頁顯示錯誤，而不是空的集數清單 |
| 選定線路沒有這一集的 url | 丟 `ScraperException` | 播放器既有邏輯會自動換下一條線路 |
| vodId 找不到 cuid（舊數字 id，或對照遺失） | 丟 `ScraperException`（**不可**是 `IOException`） | `DetailViewModel` 只對非網路錯誤呼叫 `SavedEntryRecovery`：以片名搜尋、找到就改指並重新載入；找不到才顯示既有 `detail_vod_gone`。丟成 `IOException` 會被當成網路問題，修復永遠不會觸發 |
| RSC 片段解析失敗 | 該函式回傳空值或丟錯，不影響卡片 HTML 的解析 | 混合式的用意 |
| Cloudflare 挑戰 | 既有 `CloudflareInterceptor` | 不另寫 |

---

## 8. 既有問題修正：收藏 30 天後打不開

**現況（已查證）**：
- `embed_slugs`（Jable、XNXX；本次起也含 Imaple）：對照只在列表頁寫入（`EmbeddedHlsSource.kt:137`）。
- `movieffm_slugs`：對照只在列表、搜尋結果、詳情頁的相關推薦寫入；開收藏只讀不寫。
- `DoplyApp` 每次啟動以 `DELETE … WHERE cachedAt < 30 天前` 清理兩張表，**不檢查是否被收藏或在觀看紀錄中**。
- 結果：收藏的片若 30 天沒在列表或搜尋出現，對照就被刪掉，之後永遠打不開。

**修正**：刪除條件加上「被 `favorites` 或 `watch_history` 引用的列不刪」。

```sql
DELETE FROM embed_slugs
WHERE cachedAt < :thresholdMs
  AND NOT EXISTS (SELECT 1 FROM favorites f
                  WHERE f.vodId = embed_slugs.vodId AND f.sourceType = embed_slugs.sourceType)
  AND NOT EXISTS (SELECT 1 FROM watch_history h
                  WHERE h.vodId = embed_slugs.vodId AND h.sourceType = embed_slugs.sourceType)
```

`movieffm_slugs` 沒有 `sourceType` 欄位，改為比對 `sourceType = 'MOVIEFFM'`。不改資料表結構、不需要
資料庫遷移。兩張表各一個 commit。

---

## 9. 測試策略

### 9.1 Parser 單元測試（JVM）
測試資料為 2026-10-03 實際抓取並截短的頁面：列表頁、搜尋有結果、搜尋 0 筆（含推薦卡片）、詳情頁、播放頁。

| 測試 | 為什麼重要 |
|---|---|
| 卡片數量與欄位（cuid、片名、封面補網域、更新狀態） | 列表與搜尋的基本正確性 |
| 搜尋 0 筆回空 | 否則推薦卡片會被當成搜尋結果 |
| 總數 > 0 但 0 張卡片 → 丟錯 | 否則健康偵測抓不到版型變更 |
| `knownDead`、`needsProxy` 線路被濾掉 | 避免換到已知會失敗的線路 |
| 集數序號依位置 | 有些來源的集名是「第 0 集」 |
| 選定線路沒有該集 url → 丟錯 | 讓播放器自動換線 |
| 多段 RSC 片段串接與跳脫字元 | RSC 分多段 `push` 送出 |
| 找不到 cuid 時丟的是 `ScraperException`、不是 `IOException` | 丟錯型別，舊收藏的片名找回就永遠不會觸發 |

### 9.2 清理 SQL 測試（JVM + sqlite-jdbc）
- 兩句刪除 SQL 抽成 DAO 內的 `const val`，`@Query` 與測試共用同一字串，避免測試測的不是正式那一句。
- `sqlite-jdbc` 只加在 `testImplementation`，不進 App。
- 案例：舊且未被引用 → 刪；舊但被收藏 → 留；舊但在觀看紀錄 → 留；新的 → 留；不同 sourceType 的同 vodId 收藏不能保護到別的來源的列。

### 9.3 模擬器端到端驗收（release 同簽章覆蓋安裝）
- 首頁三列（動漫、韓劇、電影）有內容，標籤 IMP
- 分類瀏覽能翻頁
- 搜尋結果只出現一份，沒有 EY、KB
- 詳情頁有線路與集數，播放有畫面與聲音
- 設定頁來源管理：Imaple 健康，Eyny、Kubo 不再出現
- 收藏一部片 → 重開 App → 仍打得開
- 模擬器上若有改版前的 Imaple／Eyny／Kubo 收藏或紀錄：點開後應自動找回並開啟（標籤變成 IMP）

### 9.4 發版門檻
- 單元測試全過、release 建置成功、每個 commit 之後全部測試都是綠的。

---

## 10. 風險

| 風險 | 緩解 |
|---|---|
| RSC 是 Next.js 內部格式，站方改版可能整段變動 | 卡片 HTML 與 RSC 分開解析；版型變更時丟錯讓健康偵測抓到 |
| 「只有指定線路帶全部集數 url」不直觀，容易漏 | 取流一律以該集、該線路的網址請求；有測試案例 |
| 第三方 m3u8 CDN 本身會死或很慢 | 既有的自動換線；不在本次範圍 |
| cuid 的長期穩定性未驗證（只確認多次請求與跨站一致） | 若日後會變，既有 `SavedEntryRecovery` 會以片名自動找回 |
| 片名找回只比對第一頁搜尋結果、以片名正規化後相等為準 | 同名作品可能對錯；沿用既有機制的行為，不在本次調整 |
| 同一來源在首頁出現三列，可能撞到 UI 的 key 假設 | 實作時先確認 `MORE_SOURCES` 的 key 組成 |

---

## 11. Commit 切分

1. 修正 `embed_slugs` 清理會刪掉收藏對照（含 sqlite-jdbc 測試依賴與測試）
2. 修正 `movieffm_slugs` 清理會刪掉收藏對照
3. 新增新平台 parser 與測試資料、單元測試
4. `ImapleTvSource` 改接新平台，分類對應改寫
5. 三站合併：取消註冊 EY／KB、首頁三列、設定頁、`EndpointResolver` 與 `endpoints.json`
6. 刪除三站的舊 MacCMS 程式碼
