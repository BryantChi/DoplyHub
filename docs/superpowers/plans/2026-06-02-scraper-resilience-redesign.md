# Scraper 失效修復 + 解析感知健康探測機制 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修復 gimytv / gimymax / eyny_tv 三個改版失效的 scraper 與 gimy_tw 線路名退化,並將 `EndpointResolver` 探測升級為「parser-based 健康驗證 + 自動選可解析候選網址」。

**Architecture:** 把各站列表/詳情解析抽成純函式物件(吃 `baseUrl` 參數、不碰網路),source 委派之;測試用 inline HTML fixture 直接斷言純函式輸出。健康探測在 `SiteSource` 加帶預設值的 `probeListCount(baseUrl)`,壞站/改版回 0、健康站回實際筆數;`EndpointResolver` 透過 Hilt multibinding + `Provider`(破 DI 循環)取得 sources,挑能解析的候選網址。

**Tech Stack:** Kotlin、Jsoup(純 JVM 可單元測試)、JUnit + Truth、Hilt(`@IntoMap` 多重綁定 + `Provider`)、OkHttp。

**參考 spec:** `docs/superpowers/specs/2026-06-02-scraper-resilience-redesign.md`

---

## File Structure

**新增:**
- `app/src/main/java/com/gimy/tv/data/scraper/parser/GimyTvParser.kt` — gimyplus 列表/詳情純解析
- `app/src/main/java/com/gimy/tv/data/scraper/parser/GimyMaxParser.kt` — gimy01 列表/詳情純解析
- `app/src/main/java/com/gimy/tv/data/scraper/parser/EynyTvParser.kt` — eynytv 列表/詳情純解析
- `app/src/main/java/com/gimy/tv/data/scraper/parser/MacCmsEpisodeParser.kt` — MacCMS 集數線路純解析(供 gimy_tw 修復共用)
- `app/src/test/kotlin/com/gimy/tv/data/scraper/GimyTvParserTest.kt`
- `app/src/test/kotlin/com/gimy/tv/data/scraper/GimyMaxParserTest.kt`
- `app/src/test/kotlin/com/gimy/tv/data/scraper/EynyTvParserTest.kt`
- `app/src/test/kotlin/com/gimy/tv/data/scraper/MacCmsEpisodeParserTest.kt`
- `app/src/test/kotlin/com/gimy/tv/data/endpoint/EndpointHealthTest.kt`

**修改:**
- `GimyTvSource.kt` / `GimyMaxSource.kt` / `EynyTvSource.kt` — 委派至 parser、新增 `probeListCount`
- `MacCmsListBasedSource.kt` — 委派集數解析至 `MacCmsEpisodeParser`、新增 `probeListCount`
- `SiteSource.kt` — 新增 `probeListCount` 帶預設值
- `EndpointResolver.kt` — `pickBestEndpoint` 改 parser-based、注入 sources
- `RepositoryModule.kt` — `@IntoMap` 綁定 sources
- `di/ScraperModule.kt`(若無則新增)— `SourceTypeKey` map key 定義
- `endpoints.json` — 三個新網域(gimytv 已改、gimymax 已改,確認)

---

## Phase 1 — Parser 抽取 + gimytv 重寫

### Task 1: GimyTvParser 列表解析(gimyplus.com 新模板)

**Files:**
- Create: `app/src/main/java/com/gimy/tv/data/scraper/parser/GimyTvParser.kt`
- Test: `app/src/test/kotlin/com/gimy/tv/data/scraper/GimyTvParserTest.kt`

- [ ] **Step 1: 寫失敗測試**

實測結構:`<a href="/vod/443968.html" class="card__thumb" aria-label="雨霖鈴"><img src="https://imgs.1777cdn.com/...jpg" alt="雨霖鈴"><span class="card__badge">更新至33集</span></a>` + 同卡 `<a class="card__body"><h3 class="card__title">雨霖鈴</h3></a>`。

```kotlin
package com.gimy.tv.data.scraper

import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.data.scraper.parser.GimyTvParser
import com.google.common.truth.Truth.assertThat
import org.jsoup.Jsoup
import org.junit.Test

class GimyTvParserTest {
    private val baseUrl = "https://gimyplus.com"

    private val listHtml = """
        <div class="grid">
          <article>
            <a href="/vod/443968.html" class="card__thumb" aria-label="雨霖鈴">
              <img src="https://imgs.1777cdn.com/upload/vod/a.jpg" alt="雨霖鈴">
              <span class="card__badge">更新至33集</span>
            </a>
            <a href="/vod/443968.html" class="card__body">
              <h3 class="card__title">雨霖鈴</h3>
            </a>
          </article>
          <article>
            <a href="/vod/100.html" class="card__thumb" aria-label="電影X">
              <img src="/upload/vod/b.jpg" alt="電影X">
              <span class="card__badge">HD</span>
            </a>
            <a href="/vod/100.html" class="card__body"><h3 class="card__title">電影X</h3></a>
          </article>
        </div>
    """.trimIndent()

    @Test fun `parses cards into vods with id title cover status`() {
        val doc = Jsoup.parse(listHtml, baseUrl)
        val result = GimyTvParser.parseVodList(doc, baseUrl, page = 1)
        assertThat(result.items).hasSize(2)
        val first = result.items[0]
        assertThat(first.id).isEqualTo(443968L)
        assertThat(first.title).isEqualTo("雨霖鈴")
        assertThat(first.coverUrl).isEqualTo("https://imgs.1777cdn.com/upload/vod/a.jpg")
        assertThat(first.status).isEqualTo("更新至33集")
        assertThat(first.sourceType).isEqualTo(SourceType.GIMYTV)
    }

    @Test fun `resolves root-relative cover against baseUrl`() {
        val doc = Jsoup.parse(listHtml, baseUrl)
        val result = GimyTvParser.parseVodList(doc, baseUrl, page = 1)
        assertThat(result.items[1].coverUrl).isEqualTo("https://gimyplus.com/upload/vod/b.jpg")
    }
}
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `./gradlew :app:testDebugUnitTest --tests "com.gimy.tv.data.scraper.GimyTvParserTest"`
Expected: FAIL — `GimyTvParser` 未定義 / unresolved reference。

- [ ] **Step 3: 實作 GimyTvParser.parseVodList**

```kotlin
package com.gimy.tv.data.scraper.parser

import com.gimy.tv.domain.model.PaginatedResult
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.domain.util.parseEpisodeStatus
import org.jsoup.nodes.Document

/** 純解析 gimyplus.com(GIMYTV)新模板,不碰網路。Source 委派之,測試直接呼叫。 */
object GimyTvParser {
    private val vodIdRegex = Regex("/vod/(\\d+)\\.html")

    fun parseVodList(doc: Document, baseUrl: String, page: Int): PaginatedResult<Vod> {
        doc.select("#stickyside, .rankings, .rank-list").remove()
        val items = mutableListOf<Vod>()
        for (card in doc.select("a.card__thumb[href*=/vod/]")) {
            val id = vodIdRegex.find(card.attr("href"))?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val title = card.attr("aria-label").ifBlank {
                card.selectFirst("img")?.attr("alt").orEmpty()
            }.ifBlank {
                card.parent()?.selectFirst("a.card__body h3.card__title")?.text().orEmpty()
            }.trim()
            if (title.isBlank()) continue
            val cover = resolveUrl(card.selectFirst("img")?.attr("src").orEmpty(), baseUrl)
            val status = card.selectFirst("span.card__badge")?.text()?.trim() ?: ""
            items.add(Vod(id, SourceType.GIMYTV, title, cover, "", 0, status,
                siteStatus = parseEpisodeStatus(status)))
        }
        val unique = items.distinctBy { it.id }
        val hasNext = doc.select("a:contains(下一頁), a[title=下一頁], .chip-nav--next").isNotEmpty()
        val totalPages = Regex("(\\d+)/(\\d+)").find(doc.select(".page, .stui-page, .section__nav").text())
            ?.groupValues?.get(2)?.toIntOrNull() ?: if (hasNext) page + 1 else page
        return PaginatedResult(unique, page, totalPages, hasNext || page < totalPages)
    }

    internal fun resolveUrl(url: String, baseUrl: String): String = when {
        url.isBlank() -> ""
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$baseUrl$url"
        else -> url
    }
}
```

- [ ] **Step 4: 跑測試確認通過**

Run: `./gradlew :app:testDebugUnitTest --tests "com.gimy.tv.data.scraper.GimyTvParserTest"`
Expected: PASS(2 tests)。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/gimy/tv/data/scraper/parser/GimyTvParser.kt \
        app/src/test/kotlin/com/gimy/tv/data/scraper/GimyTvParserTest.kt
git commit -m "feat: GimyTvParser 列表解析改 gimyplus 新模板(card__thumb)"
```

### Task 2: GimyTvParser 詳情解析(線路 + 集數)

**Files:**
- Modify: `app/src/main/java/com/gimy/tv/data/scraper/parser/GimyTvParser.kt`
- Test: `app/src/test/kotlin/com/gimy/tv/data/scraper/GimyTvParserTest.kt`

實測:`<div class="playlist-block"><div class="playlist-block__head"><span class="playlist-block__title">高清線路 ᴴᴰ</span></div><div class="playlist-grid"><a href="/ep/443968-1-1.html">第1集</a>...</div></div>`。線路名去 ` ᴴᴰ`;sourceId = `/ep/{vod}-{src}-{ep}` 中段。

- [ ] **Step 1: 寫失敗測試**

```kotlin
    private val detailHtml = """
        <html><head><meta property="og:image" content="https://imgs.1777cdn.com/c.jpg"></head>
        <body>
          <h1>雨霖鈴</h1>
          <p>導演： 張三 主演： 楊洋,章若楠 年代： 2026 類型： 陸劇 狀態： 更新至33集</p>
          <p>這是一段夠長的劇情介紹文字用來通過長度門檻超過五十個字所以多寫一些內容填充長度。</p>
          <div class="playlist-block">
            <div class="playlist-block__head"><span class="playlist-block__title">高清線路 ᴴᴰ</span></div>
            <div class="playlist-grid">
              <a href="/ep/443968-1-1.html">第1集</a>
              <a href="/ep/443968-1-2.html">第2集</a>
            </div>
          </div>
          <div class="playlist-block">
            <div class="playlist-block__head"><span class="playlist-block__title">優酷線路 ᴴᴰ</span></div>
            <div class="playlist-grid"><a href="/ep/443968-8-1.html">第1集</a></div>
          </div>
        </body></html>
    """.trimIndent()

    @Test fun `parses detail title cover and line groups`() {
        val doc = Jsoup.parse(detailHtml, baseUrl)
        val detail = GimyTvParser.parseVodDetail(doc, vodId = 443968L, baseUrl)
        assertThat(detail.vod.title).isEqualTo("雨霖鈴")
        assertThat(detail.vod.coverUrl).isEqualTo("https://imgs.1777cdn.com/c.jpg")
        assertThat(detail.episodes).hasSize(2)
        val hd = detail.episodes.first { it.sourceName == "高清線路" }
        assertThat(hd.sourceId).isEqualTo(1)
        assertThat(hd.episodes).hasSize(2)
        assertThat(hd.episodes[0].playUrl).isEqualTo("/ep/443968-1-1.html")
        assertThat(detail.episodes.first { it.sourceName == "優酷線路" }.sourceId).isEqualTo(8)
    }
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `./gradlew :app:testDebugUnitTest --tests "com.gimy.tv.data.scraper.GimyTvParserTest"`
Expected: FAIL — `parseVodDetail` 未定義。

- [ ] **Step 3: 實作 parseVodDetail(加到 GimyTvParser)**

```kotlin
    private val stabilityOrder = listOf("順暢", "無盡", "極速", "高清", "騰訊", "藍光", "4K", "優質", "非凡")
    private val epRegex = Regex("/ep/\\d+-(\\d+)-(\\d+)\\.html")

    fun parseVodDetail(doc: Document, vodId: Long, baseUrl: String): com.gimy.tv.domain.model.VodDetail {
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.let { resolveUrl(it, baseUrl) } ?: ""
        val body = doc.body().text()
        val director = extractMeta(body, "導演")
        val actors = extractMeta(body, "主演").split(Regex("[,，/、]")).map { it.trim() }.filter { it.isNotBlank() }
        val year = (extractMeta(body, "年代") + extractMeta(body, "年份")).filter { it.isDigit() }.take(4).toIntOrNull() ?: 0
        val category = extractMeta(body, "類型").ifBlank { extractMeta(body, "分類") }
        val status = extractMeta(body, "狀態")
        val synopsis = doc.select("p").firstOrNull { it.text().length > 50 && !it.text().contains("導演") }?.text()?.trim() ?: ""

        val groups = mutableListOf<com.gimy.tv.domain.model.EpisodeGroup>()
        for (block in doc.select("div.playlist-block")) {
            val name = block.selectFirst(".playlist-block__title")?.text()?.trim()
                ?.replace(Regex("\\s*ᴴᴰ\\s*"), "")?.trim() ?: continue
            val eps = mutableListOf<com.gimy.tv.domain.model.Episode>(); var sId = 0
            for (link in block.select("a[href~=/ep/]")) {
                val m = epRegex.find(link.attr("href")) ?: continue
                sId = m.groupValues[1].toIntOrNull() ?: continue
                val n = m.groupValues[2].toIntOrNull() ?: continue
                eps.add(com.gimy.tv.domain.model.Episode(n, link.text().trim(), link.attr("href")))
            }
            if (eps.isNotEmpty() && name.isNotBlank())
                groups.add(com.gimy.tv.domain.model.EpisodeGroup(name, sId, eps.sortedBy { it.number }))
        }
        val sorted = groups.sortedWith(compareByDescending { g ->
            val i = stabilityOrder.indexOfFirst { g.sourceName.contains(it) }; if (i >= 0) stabilityOrder.size - i else -1
        })
        return com.gimy.tv.domain.model.VodDetail(
            com.gimy.tv.domain.model.Vod(vodId, SourceType.GIMYTV, title, cover, category, year, status,
                siteStatus = parseEpisodeStatus(status)),
            director, actors, synopsis, sorted)
    }

    private fun extractMeta(text: String, key: String): String =
        Regex("$key[：:]\\s*(.+?)(?=\\s+\\S+[：:]|$)").find(text)?.groupValues?.get(1)?.trim() ?: ""
```

- [ ] **Step 4: 跑測試確認通過**

Run: `./gradlew :app:testDebugUnitTest --tests "com.gimy.tv.data.scraper.GimyTvParserTest"`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/gimy/tv/data/scraper/parser/GimyTvParser.kt \
        app/src/test/kotlin/com/gimy/tv/data/scraper/GimyTvParserTest.kt
git commit -m "feat: GimyTvParser 詳情解析改 playlist-block 線路結構"
```

### Task 3: GimyTvSource 委派至 parser + 移除舊解析

**Files:**
- Modify: `app/src/main/java/com/gimy/tv/data/scraper/GimyTvSource.kt`

- [ ] **Step 1: 改 fetchVodList / search / fetchVodDetail 委派**

把 `parseVodList`/`parseSearchResults`/`parseVodDetail` 私有方法刪除,改呼叫 `GimyTvParser`。`baseUrl` 用既有 getter。保留 `fetchHtml`/`fetchDocument`/`parsePlayerData`(取流不變)。

```kotlin
    override suspend fun fetchVodList(typeId: Int, page: Int): PaginatedResult<Vod> =
        withContext(Dispatchers.IO) {
            val url = if (page <= 1) "$baseUrl/type/$typeId.html" else "$baseUrl/type/$typeId-$page.html"
            GimyTvParser.parseVodList(fetchDocument(url), baseUrl, page)
        }

    override suspend fun fetchVodDetail(vodId: Long): VodDetail =
        withContext(Dispatchers.IO) {
            GimyTvParser.parseVodDetail(fetchDocument("$baseUrl/vod/$vodId.html"), vodId, baseUrl)
        }

    override suspend fun search(keyword: String, page: Int): PaginatedResult<Vod> =
        withContext(Dispatchers.IO) {
            val enc = java.net.URLEncoder.encode(keyword, "UTF-8")
            val doc = fetchDocument("$baseUrl/search/$enc----------$page---.html")
            doc.select("#stickyside").remove()
            GimyTvParser.parseVodList(doc, baseUrl, page)
        }
```

刪除已搬走的私有方法(`parseVodList`、`parseSearchResults`、`parseVodDetail`、`parseSeriesVods`、`resolveUrl`、`extractMeta`、`stabilityOrder`)。保留 `parsePlayerData` 及其 import。

- [ ] **Step 2: 編譯確認**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL(無 unresolved reference)。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/gimy/tv/data/scraper/GimyTvSource.kt
git commit -m "refactor: GimyTvSource 委派解析至 GimyTvParser"
```

---

## Phase 2 — gimymax 重寫(gimy01.co,含 /eps/ 路徑)

### Task 4: GimyMaxParser 列表解析

**Files:**
- Create: `app/src/main/java/com/gimy/tv/data/scraper/parser/GimyMaxParser.kt`
- Test: `app/src/test/kotlin/com/gimy/tv/data/scraper/GimyMaxParserTest.kt`

實測:`<a class="poster" href="/vod/452154.html"><span class="poster__thumb"><img src="https://cdn.picsu.pics/...jpg" alt="家業"><span class="poster__status">更新至34集</span></span><h3 class="poster__title">家業</h3></a>`。

- [ ] **Step 1: 寫失敗測試**

```kotlin
package com.gimy.tv.data.scraper

import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.data.scraper.parser.GimyMaxParser
import com.google.common.truth.Truth.assertThat
import org.jsoup.Jsoup
import org.junit.Test

class GimyMaxParserTest {
    private val baseUrl = "https://gimy01.co"
    private val listHtml = """
        <div class="grid">
          <a class="poster" href="/vod/452154.html">
            <span class="poster__thumb">
              <img src="https://cdn.picsu.pics/upload/vod/a.jpg" alt="家業">
              <span class="poster__status">更新至34集</span>
            </span>
            <h3 class="poster__title">家業</h3>
            <p class="poster__meta">楊紫</p>
          </a>
        </div>
    """.trimIndent()

    @Test fun `parses poster cards`() {
        val doc = Jsoup.parse(listHtml, baseUrl)
        val r = GimyMaxParser.parseVodList(doc, baseUrl, 1)
        assertThat(r.items).hasSize(1)
        val v = r.items[0]
        assertThat(v.id).isEqualTo(452154L)
        assertThat(v.title).isEqualTo("家業")
        assertThat(v.coverUrl).isEqualTo("https://cdn.picsu.pics/upload/vod/a.jpg")
        assertThat(v.status).isEqualTo("更新至34集")
        assertThat(v.sourceType).isEqualTo(SourceType.GIMYMAX)
    }
}
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `./gradlew :app:testDebugUnitTest --tests "com.gimy.tv.data.scraper.GimyMaxParserTest"`
Expected: FAIL — `GimyMaxParser` 未定義。

- [ ] **Step 3: 實作 GimyMaxParser.parseVodList**

```kotlin
package com.gimy.tv.data.scraper.parser

import com.gimy.tv.domain.model.PaginatedResult
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.domain.util.parseEpisodeStatus
import org.jsoup.nodes.Document

object GimyMaxParser {
    private val vodIdRegex = Regex("/vod/(\\d+)\\.html")

    fun parseVodList(doc: Document, baseUrl: String, page: Int): PaginatedResult<Vod> {
        doc.select("#stickyside, .rankings").remove()
        val items = mutableListOf<Vod>()
        for (card in doc.select("a.poster[href*=/vod/]")) {
            val id = vodIdRegex.find(card.attr("href"))?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val title = card.selectFirst("h3.poster__title")?.text()?.trim()
                ?.ifBlank { card.selectFirst("img")?.attr("alt")?.trim() } ?: ""
            if (title.isBlank()) continue
            val cover = resolveUrl(card.selectFirst(".poster__thumb img")?.attr("src").orEmpty(), baseUrl)
            val status = card.selectFirst("span.poster__status")?.text()?.trim() ?: ""
            items.add(Vod(id, SourceType.GIMYMAX, title, cover, "", 0, status,
                siteStatus = parseEpisodeStatus(status)))
        }
        val unique = items.distinctBy { it.id }
        val hasNext = doc.select("a:contains(下一頁), a.next, a[title=下一頁]").isNotEmpty()
        val totalPages = Regex("(\\d+)/(\\d+)").find(doc.select(".page, .pagination").text())
            ?.groupValues?.get(2)?.toIntOrNull() ?: if (hasNext) page + 1 else page
        return PaginatedResult(unique, page, totalPages, hasNext || page < totalPages)
    }

    internal fun resolveUrl(url: String, baseUrl: String): String = when {
        url.isBlank() -> ""
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$baseUrl$url"
        else -> url
    }
}
```

- [ ] **Step 4: 跑測試確認通過**

Run: `./gradlew :app:testDebugUnitTest --tests "com.gimy.tv.data.scraper.GimyMaxParserTest"`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/gimy/tv/data/scraper/parser/GimyMaxParser.kt \
        app/src/test/kotlin/com/gimy/tv/data/scraper/GimyMaxParserTest.kt
git commit -m "feat: GimyMaxParser 列表解析改 gimy01 新模板(poster)"
```

### Task 5: GimyMaxParser 詳情解析(block + /eps/ 路徑)

**Files:**
- Modify: `app/src/main/java/com/gimy/tv/data/scraper/parser/GimyMaxParser.kt`
- Test: `app/src/test/kotlin/com/gimy/tv/data/scraper/GimyMaxParserTest.kt`

實測:線路名在 `.sources .source` chips(高清線路ᴴᴰ / 4K畫質線路ᴴᴰ / 如意雲…);集數在 `div.block` 內 `div#{sourceId}`,連結 `/eps/{vod}-{src}-{ep}.html`。chips 與 block 用 sourceId(連結中段)對應線路名。

- [ ] **Step 1: 寫失敗測試**

```kotlin
    private val detailHtml = """
        <html><head><meta property="og:image" content="https://cdn.picsu.pics/c.jpg"></head>
        <body>
          <h1>家業</h1>
          <p>導演： 李四 主演： 楊紫 年代： 2026 類型： 陸劇 狀態： 更新至34集</p>
          <p>這是一段夠長的劇情介紹用來通過五十字長度門檻所以要多打一些字進來填充它的長度喔。</p>
          <div class="sources route-chips">
            <a class="source is-active" data-id="1">高清線路ᴴᴰ</a>
            <a class="source" data-id="3">無盡雲</a>
          </div>
          <div class="block"><div class="block__head">選集播放</div>
            <div id="1"><a href="/eps/452154-1-1.html">第1集</a><a href="/eps/452154-1-2.html">第2集</a></div>
            <div id="3"><a href="/eps/452154-3-1.html">第1集</a></div>
          </div>
        </body></html>
    """.trimIndent()

    @Test fun `parses detail with eps path and source chip names`() {
        val doc = Jsoup.parse(detailHtml, baseUrl)
        val d = GimyMaxParser.parseVodDetail(doc, 452154L, baseUrl)
        assertThat(d.vod.title).isEqualTo("家業")
        assertThat(d.episodes).hasSize(2)
        val hd = d.episodes.first { it.sourceId == 1 }
        assertThat(hd.sourceName).isEqualTo("高清線路")
        assertThat(hd.episodes[0].playUrl).isEqualTo("/eps/452154-1-1.html")
        assertThat(d.episodes.first { it.sourceId == 3 }.sourceName).isEqualTo("無盡雲")
    }
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `./gradlew :app:testDebugUnitTest --tests "com.gimy.tv.data.scraper.GimyMaxParserTest"`
Expected: FAIL — `parseVodDetail` 未定義。

- [ ] **Step 3: 實作 parseVodDetail(加到 GimyMaxParser)**

線路名來源:先用 `.sources .source[data-id]` 建 `sourceId → 線路名` 表(去 `ᴴᴰ`);集數從 `div.block div[id]` 依 id 取出,id 即 sourceId;線路名查表,查不到用連結中段 fallback「線路 N」。

```kotlin
    private val stabilityOrder = listOf("順暢", "無盡", "極速", "高清", "騰訊", "藍光", "4K", "優質", "非凡")
    private val epsRegex = Regex("/eps/\\d+-(\\d+)-(\\d+)\\.html")

    fun parseVodDetail(doc: Document, vodId: Long, baseUrl: String): com.gimy.tv.domain.model.VodDetail {
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { resolveUrl(it, baseUrl) } ?: ""
        val body = doc.body().text()
        val director = extractMeta(body, "導演")
        val actors = extractMeta(body, "主演").split(Regex("[,，/、]")).map { it.trim() }.filter { it.isNotBlank() }
        val year = (extractMeta(body, "年代") + extractMeta(body, "年份")).filter { it.isDigit() }.take(4).toIntOrNull() ?: 0
        val category = extractMeta(body, "類型").ifBlank { extractMeta(body, "分類") }
        val status = extractMeta(body, "狀態")
        val synopsis = doc.select("p").firstOrNull { it.text().length > 50 && !it.text().contains("導演") }?.text()?.trim() ?: ""

        val nameById = mutableMapOf<Int, String>()
        for (chip in doc.select(".sources .source[data-id]")) {
            val sid = chip.attr("data-id").toIntOrNull() ?: continue
            nameById[sid] = chip.text().trim().replace(Regex("\\s*ᴴᴰ\\s*"), "").trim()
        }
        val groups = mutableListOf<com.gimy.tv.domain.model.EpisodeGroup>()
        for (blk in doc.select("div.block div[id]")) {
            val sid = blk.id().toIntOrNull() ?: continue
            val eps = mutableListOf<com.gimy.tv.domain.model.Episode>()
            for (link in blk.select("a[href~=/eps/]")) {
                val m = epsRegex.find(link.attr("href")) ?: continue
                val n = m.groupValues[2].toIntOrNull() ?: continue
                eps.add(com.gimy.tv.domain.model.Episode(n, link.text().trim(), link.attr("href")))
            }
            if (eps.isNotEmpty()) {
                val name = nameById[sid] ?: "線路 $sid"
                groups.add(com.gimy.tv.domain.model.EpisodeGroup(name, sid, eps.sortedBy { it.number }))
            }
        }
        val sorted = groups.sortedWith(compareByDescending { g ->
            val i = stabilityOrder.indexOfFirst { g.sourceName.contains(it) }; if (i >= 0) stabilityOrder.size - i else -1
        })
        return com.gimy.tv.domain.model.VodDetail(
            com.gimy.tv.domain.model.Vod(vodId, SourceType.GIMYMAX, title, cover, category, year, status,
                siteStatus = parseEpisodeStatus(status)),
            director, actors, synopsis, sorted)
    }

    private fun extractMeta(text: String, key: String): String =
        Regex("$key[：:]\\s*(.+?)(?=\\s+\\S+[：:]|$)").find(text)?.groupValues?.get(1)?.trim() ?: ""
```

- [ ] **Step 4: 跑測試確認通過**

Run: `./gradlew :app:testDebugUnitTest --tests "com.gimy.tv.data.scraper.GimyMaxParserTest"`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/gimy/tv/data/scraper/parser/GimyMaxParser.kt \
        app/src/test/kotlin/com/gimy/tv/data/scraper/GimyMaxParserTest.kt
git commit -m "feat: GimyMaxParser 詳情解析改 block 結構 + /eps/ 路徑"
```

### Task 6: GimyMaxSource 委派 + 確認 /eps/ 取流

**Files:**
- Modify: `app/src/main/java/com/gimy/tv/data/scraper/GimyMaxSource.kt`

- [ ] **Step 1: 委派列表/詳情/搜尋至 GimyMaxParser**

比照 Task 3:`fetchVodList`/`fetchVodDetail`/`search` 改呼叫 `GimyMaxParser`,刪除搬走的私有方法(`parseVodList`/`parseSearchResults`/`parseVodDetail`/`parseRelatedVods`/`parseSeriesVods`/`parsePlaylistMobile`/`resolveUrl`/`extractMeta`/`stabilityOrder`)。保留 `parsePlayerData`(取流不變,`fetchPlayerData` 收到的是 parser 產出的 `/eps/...` 相對路徑,既有 `if (episodeUrl.startsWith("http")) episodeUrl else "$baseUrl$episodeUrl"` 邏輯自然正確)。保留 `class ScraperException`。

- [ ] **Step 2: 編譯確認**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/gimy/tv/data/scraper/GimyMaxSource.kt
git commit -m "refactor: GimyMaxSource 委派解析至 GimyMaxParser"
```

---

## Phase 3 — eyny_tv 重寫(eynytv.com,module/dianyingim 模板)

### Task 7: EynyTvParser 列表解析(img 為兄弟節點)

**Files:**
- Create: `app/src/main/java/com/gimy/tv/data/scraper/parser/EynyTvParser.kt`
- Test: `app/src/test/kotlin/com/gimy/tv/data/scraper/EynyTvParserTest.kt`

實測:`<div class="module-item">...<a href="/voddetail/304462.html" title="奪回"><i class="icon-play"></i></a><img class="lazy lazyloaded" data-src="https://img.avdb.me/...jpg" src="loading.png" alt="奪回">...<div class="module-item-caption"><span>2025</span>...</div></div>`。連結與 img 是兄弟、不是父子。

- [ ] **Step 1: 寫失敗測試**

```kotlin
package com.gimy.tv.data.scraper

import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.data.scraper.parser.EynyTvParser
import com.google.common.truth.Truth.assertThat
import org.jsoup.Jsoup
import org.junit.Test

class EynyTvParserTest {
    private val baseUrl = "https://eynytv.com"
    private val listHtml = """
        <div class="module-list">
          <div class="module-item">
            <div class="module-item-pic">
              <a href="/voddetail/304462.html" title="奪回"><i class="icon-play"></i></a>
              <img class="lazy" data-src="https://img.avdb.me/chinaq/a.jpg" src="/loading.png" alt="奪回">
            </div>
            <div class="module-item-caption"><span>2025</span><span class="video-class">劇情</span></div>
          </div>
        </div>
    """.trimIndent()

    @Test fun `parses module-item cards with data-src cover`() {
        val doc = Jsoup.parse(listHtml, baseUrl)
        val r = EynyTvParser.parseVodList(doc, baseUrl, 1)
        assertThat(r.items).hasSize(1)
        val v = r.items[0]
        assertThat(v.id).isEqualTo(304462L)
        assertThat(v.title).isEqualTo("奪回")
        assertThat(v.coverUrl).isEqualTo("https://img.avdb.me/chinaq/a.jpg")
        assertThat(v.sourceType).isEqualTo(SourceType.EYNY_TV)
    }
}
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `./gradlew :app:testDebugUnitTest --tests "com.gimy.tv.data.scraper.EynyTvParserTest"`
Expected: FAIL — `EynyTvParser` 未定義。

- [ ] **Step 3: 實作 EynyTvParser.parseVodList**

以 `.module-item` 為單位,連結取 `a[href*=/voddetail/]`,封面取同卡 `img[data-src]`(lazy)。

```kotlin
package com.gimy.tv.data.scraper.parser

import com.gimy.tv.domain.model.PaginatedResult
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.domain.util.parseEpisodeStatus
import org.jsoup.nodes.Document

object EynyTvParser {
    private val vodIdRegex = Regex("/voddetail/(\\d+)\\.html")

    fun parseVodList(doc: Document, baseUrl: String, page: Int): PaginatedResult<Vod> {
        doc.select("#stickyside, .myui-side, aside").remove()
        val items = mutableListOf<Vod>()
        for (item in doc.select(".module-item")) {
            val link = item.selectFirst("a[href*=/voddetail/]") ?: continue
            val id = vodIdRegex.find(link.attr("href"))?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val title = link.attr("title").trim().ifBlank {
                item.selectFirst("img")?.attr("alt")?.trim().orEmpty()
            }
            if (title.isBlank()) continue
            val img = item.selectFirst("img")
            val cover = resolveUrl(
                img?.attr("data-src").orEmpty().ifBlank { img?.attr("data-original").orEmpty() }, baseUrl)
            val status = item.selectFirst(".module-item-note, .pic-text, span.note")?.text()?.trim() ?: ""
            items.add(Vod(id, SourceType.EYNY_TV, title, cover, "", 0, status,
                siteStatus = parseEpisodeStatus(status)))
        }
        val unique = items.distinctBy { it.id }
        val hasNext = doc.select("a:contains(下一頁), a:contains(下一页), a.next").isNotEmpty()
        val totalPages = Regex("(\\d+)/(\\d+)").find(doc.select(".myui-page, .page, .pagination").text())
            ?.groupValues?.get(2)?.toIntOrNull() ?: if (hasNext) page + 1 else page
        return PaginatedResult(unique, page, totalPages, hasNext || page < totalPages)
    }

    internal fun resolveUrl(url: String, baseUrl: String): String = when {
        url.isBlank() -> ""
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$baseUrl$url"
        else -> url
    }
}
```

- [ ] **Step 4: 跑測試確認通過**

Run: `./gradlew :app:testDebugUnitTest --tests "com.gimy.tv.data.scraper.EynyTvParserTest"`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/gimy/tv/data/scraper/parser/EynyTvParser.kt \
        app/src/test/kotlin/com/gimy/tv/data/scraper/EynyTvParserTest.kt
git commit -m "feat: EynyTvParser 列表解析改 module-item 模板"
```

### Task 8: EynyTvParser 詳情線路解析(module-tab)

**Files:**
- Modify: `app/src/main/java/com/gimy/tv/data/scraper/parser/EynyTvParser.kt`
- Test: `app/src/test/kotlin/com/gimy/tv/data/scraper/EynyTvParserTest.kt`

實測:線路 tab 在 `.module-tab.module-player-tab .module-tab-item`,集數清單 `.module-list.module-player-list a[href*=/vodplay/]`,連結 `/vodplay/{vod}-{src}-{ep}.html`。tab 與清單依出現順序對應(第 i 個 tab ↔ 第 i 個 player-list)。

- [ ] **Step 1: 寫失敗測試**

```kotlin
    private val detailHtml = """
        <html><head><meta property="og:image" content="https://img.avdb.me/c.jpg"></head>
        <body>
          <h1>奪回</h1>
          <p>導演： 王五 主演： 演員A 年代： 2025 類型： 美劇 狀態： 全10集</p>
          <p>這是一段夠長的劇情用以通過長度門檻所以我必須再多打一些中文字湊到超過五十個字元喔好。</p>
          <div class="module-tab module-player-tab">
            <div class="module-tab-item"><span>卧龍雲</span></div>
            <div class="module-tab-item"><span>索尼雲</span></div>
          </div>
          <div class="module-list module-player-list">
            <a href="/vodplay/304462-1-1.html">第1集</a><a href="/vodplay/304462-1-2.html">第2集</a>
          </div>
          <div class="module-list module-player-list">
            <a href="/vodplay/304462-2-1.html">第1集</a>
          </div>
        </body></html>
    """.trimIndent()

    @Test fun `parses module-tab lines with vodplay episodes`() {
        val doc = Jsoup.parse(detailHtml, baseUrl)
        val d = EynyTvParser.parseVodDetail(doc, 304462L, baseUrl)
        assertThat(d.episodes).hasSize(2)
        val wolong = d.episodes.first { it.sourceName == "卧龍雲" }
        assertThat(wolong.episodes).hasSize(2)
        assertThat(wolong.episodes[0].playUrl).isEqualTo("/vodplay/304462-1-1.html")
        assertThat(d.episodes.first { it.sourceName == "索尼雲" }.sourceId).isEqualTo(2)
    }
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `./gradlew :app:testDebugUnitTest --tests "com.gimy.tv.data.scraper.EynyTvParserTest"`
Expected: FAIL — `parseVodDetail` 未定義。

- [ ] **Step 3: 實作 parseVodDetail(加到 EynyTvParser)**

```kotlin
    private val playRegex = Regex("/vodplay/\\d+-(\\d+)-(\\d+)\\.html")

    fun parseVodDetail(doc: Document, vodId: Long, baseUrl: String): com.gimy.tv.domain.model.VodDetail {
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { resolveUrl(it, baseUrl) } ?: ""
        val body = doc.body().text()
        val director = extractMeta(body, "導演")
        val actors = extractMeta(body, "主演").split(Regex("[,，/、]")).map { it.trim() }.filter { it.isNotBlank() }
        val year = (extractMeta(body, "年代") + extractMeta(body, "年份")).filter { it.isDigit() }.take(4).toIntOrNull() ?: 0
        val category = extractMeta(body, "類型").ifBlank { extractMeta(body, "分類") }
        val status = extractMeta(body, "狀態")
        val synopsis = doc.select("p").firstOrNull { it.text().length > 50 && !it.text().contains("導演") }?.text()?.trim() ?: ""

        val tabNames = doc.select(".module-tab.module-player-tab .module-tab-item")
            .map { it.text().trim().replace(Regex("\\s*ᴴᴰ\\s*"), "").trim() }
        val lists = doc.select(".module-list.module-player-list")
        val groups = mutableListOf<com.gimy.tv.domain.model.EpisodeGroup>()
        lists.forEachIndexed { idx, list ->
            val eps = mutableListOf<com.gimy.tv.domain.model.Episode>(); var sid = idx + 1
            for (link in list.select("a[href*=/vodplay/]")) {
                val m = playRegex.find(link.attr("href")) ?: continue
                sid = m.groupValues[1].toIntOrNull() ?: continue
                val n = m.groupValues[2].toIntOrNull() ?: continue
                eps.add(com.gimy.tv.domain.model.Episode(n, link.text().trim(), link.attr("href")))
            }
            if (eps.isNotEmpty()) {
                val name = tabNames.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: "線路 $sid"
                groups.add(com.gimy.tv.domain.model.EpisodeGroup(name, sid, eps.sortedBy { it.number }))
            }
        }
        return com.gimy.tv.domain.model.VodDetail(
            com.gimy.tv.domain.model.Vod(vodId, SourceType.EYNY_TV, title, cover, category, year, status,
                siteStatus = parseEpisodeStatus(status)),
            director, actors, synopsis, groups)
    }

    private fun extractMeta(text: String, key: String): String =
        Regex("$key[：:]\\s*(.+?)(?=\\s+\\S+[：:]|$)").find(text)?.groupValues?.get(1)?.trim() ?: ""
```

- [ ] **Step 4: 跑測試確認通過**

Run: `./gradlew :app:testDebugUnitTest --tests "com.gimy.tv.data.scraper.EynyTvParserTest"`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/gimy/tv/data/scraper/parser/EynyTvParser.kt \
        app/src/test/kotlin/com/gimy/tv/data/scraper/EynyTvParserTest.kt
git commit -m "feat: EynyTvParser 詳情解析改 module-tab 線路結構"
```

### Task 9: EynyTvSource override 列表/詳情

**Files:**
- Modify: `app/src/main/java/com/gimy/tv/data/scraper/EynyTvSource.kt`

- [ ] **Step 1: 讀現況確認 override 點**

Run: `sed -n '1,60p' app/src/main/java/com/gimy/tv/data/scraper/EynyTvSource.kt`
確認 EynyTvSource 目前如何設定 `detailUrlPath`/`playUrlPath`/`listUrlPath`,以及是否已 override 任何 parse 方法。

- [ ] **Step 2: override fetchVodList / fetchVodDetail / search 委派 EynyTvParser**

EynyTvSource 改為直接 override 這三個方法(不走基底 myui 解析),用基底的 `fetchDocument`/baseUrl,委派 `EynyTvParser`。播放沿用基底 `parsePlayerAaaa`(`fetchPlayerData` 不變)。列表/搜尋 URL 沿用基底現有 `buildListUrl`/`buildSearchUrl`。

```kotlin
    override suspend fun fetchVodList(typeId: Int, page: Int): PaginatedResult<Vod> =
        withContext(Dispatchers.IO) {
            EynyTvParser.parseVodList(fetchDocument(buildListUrl(typeId, page)), baseUrl, page)
        }

    override suspend fun fetchVodDetail(vodId: Long): VodDetail =
        withContext(Dispatchers.IO) {
            EynyTvParser.parseVodDetail(fetchDocument("$baseUrl$detailUrlPath/$vodId.html"), vodId, baseUrl)
        }

    override suspend fun search(keyword: String, page: Int): PaginatedResult<Vod> =
        withContext(Dispatchers.IO) {
            val doc = fetchDocument(buildSearchUrl(keyword, page))
            doc.select("#stickyside").remove()
            EynyTvParser.parseVodList(doc, baseUrl, page)
        }
```

> 注意:`fetchDocument`/`buildListUrl`/`buildSearchUrl` 若在基底為 `private`,需在 `MacCmsListBasedSource` 改為 `protected` 才能被子類用。實作時若編譯報存取權限,將對應成員改 `protected open`。

- [ ] **Step 3: 編譯確認**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/gimy/tv/data/scraper/EynyTvSource.kt \
        app/src/main/java/com/gimy/tv/data/scraper/MacCmsListBasedSource.kt
git commit -m "refactor: EynyTvSource override 解析委派 EynyTvParser"
```

---

## Phase 4 — gimy_tw 線路名退化修復

### Task 10: MacCmsEpisodeParser 新增 playlist-mobile 分支

**Files:**
- Create: `app/src/main/java/com/gimy/tv/data/scraper/parser/MacCmsEpisodeParser.kt`
- Test: `app/src/test/kotlin/com/gimy/tv/data/scraper/MacCmsEpisodeParserTest.kt`

實測 gimy.tw 詳情:`div.playlist-mobile.playlist` + `div.gico`(線路名「…雲」)+ `ul[id^=con_playlist_]` 內 `a[href*=/vodplay/]`。需在既有 `data-toggle=tab` 與桶式 fallback 之間插入此分支。把整個集數解析抽成純函式以便測試。

- [ ] **Step 1: 寫失敗測試**

```kotlin
package com.gimy.tv.data.scraper

import com.gimy.tv.data.scraper.parser.MacCmsEpisodeParser
import com.google.common.truth.Truth.assertThat
import org.jsoup.Jsoup
import org.junit.Test

class MacCmsEpisodeParserTest {

    private val playlistMobileHtml = """
        <div class="playlist-mobile playlist layout-box clearfix">
          <li><div class="gico 1080zyk">卧龍雲</div></li>
          <ul id="con_playlist_1"><li><a href="/vodplay/235668-1-1.html">第1集</a>
            <a href="/vodplay/235668-1-2.html">第2集</a></li></ul>
        </div>
        <div class="playlist-mobile playlist layout-box clearfix">
          <li><div class="gico">索尼雲</div></li>
          <ul id="con_playlist_2"><li><a href="/vodplay/235668-2-1.html">第1集</a></li></ul>
        </div>
    """.trimIndent()

    @Test fun `playlist-mobile branch keeps real line names not 線路 N`() {
        val doc = Jsoup.parse(playlistMobileHtml, "https://gimy.tw")
        val groups = MacCmsEpisodeParser.parse(doc, playUrlPath = "/vodplay")
        assertThat(groups.map { it.sourceName }).containsExactly("卧龍雲", "索尼雲")
        val wolong = groups.first { it.sourceName == "卧龍雲" }
        assertThat(wolong.episodes).hasSize(2)
        assertThat(wolong.episodes[0].playUrl).isEqualTo("/vodplay/235668-1-1.html")
    }

    @Test fun `data-toggle tab branch still works`() {
        val tabHtml = """
            <a href="#playlist1" data-toggle="tab">高清</a>
            <div id="playlist1"><ul><li><a href="/vodplay/5-1-1.html">第1集</a></li></ul></div>
        """.trimIndent()
        val groups = MacCmsEpisodeParser.parse(Jsoup.parse(tabHtml, "https://x"), "/vodplay")
        assertThat(groups).hasSize(1)
        assertThat(groups[0].sourceName).isEqualTo("高清")
    }
}
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `./gradlew :app:testDebugUnitTest --tests "com.gimy.tv.data.scraper.MacCmsEpisodeParserTest"`
Expected: FAIL — `MacCmsEpisodeParser` 未定義。

- [ ] **Step 3: 實作 MacCmsEpisodeParser(搬移基底邏輯 + 新分支)**

把 `MacCmsListBasedSource.parseEpisodeGroups` 的內容搬來,新增 playlist-mobile 分支(置於 tab 分支之後、fallback 之前)。

```kotlin
package com.gimy.tv.data.scraper.parser

import com.gimy.tv.domain.model.Episode
import com.gimy.tv.domain.model.EpisodeGroup
import org.jsoup.nodes.Document

object MacCmsEpisodeParser {

    fun parse(doc: Document, playUrlPath: String): List<EpisodeGroup> {
        val groups = mutableListOf<EpisodeGroup>()
        val tabIdRegex = Regex("#playlist(\\d+)")
        val playLinkRegex = Regex("$playUrlPath/\\d+-(\\d+)-(\\d+)\\.html")

        // 1) 既有:data-toggle=tab + #playlistN
        for (tab in doc.select("a[data-toggle=tab][href^=#playlist]")) {
            val tabId = tabIdRegex.find(tab.attr("href"))?.groupValues?.get(1) ?: continue
            val name = tab.text().trim().replace(Regex("\\s*ᴴᴰ\\s*"), "").replace(Regex("\\s+"), "")
            if (name.isBlank()) continue
            val container = doc.getElementById("playlist$tabId") ?: continue
            val eps = collectEps(container.select("a[href*=$playUrlPath/]"), playLinkRegex)
            if (eps.second.isNotEmpty()) groups.add(EpisodeGroup(name, eps.first, eps.second.sortedBy { it.number }))
        }

        // 2) 新:playlist-mobile + div.gico + ul#con_playlist_(gimy.tw 改版)
        if (groups.isEmpty()) {
            for (container in doc.select("div.playlist-mobile.playlist")) {
                val name = container.selectFirst("div.gico")?.text()?.trim()
                    ?.replace(Regex("\\s*ᴴᴰ\\s*"), "")?.trim() ?: continue
                val ul = container.selectFirst("ul[id^=con_playlist_]") ?: continue
                val eps = collectEps(ul.select("a[href*=$playUrlPath/]"), playLinkRegex)
                if (eps.second.isNotEmpty() && name.isNotBlank())
                    groups.add(EpisodeGroup(name, eps.first, eps.second.sortedBy { it.number }))
            }
        }

        // 3) Fallback:依 sourceId 分桶,線路名「線路 N」
        if (groups.isEmpty()) {
            val bySource = mutableMapOf<Int, MutableList<Episode>>()
            for (link in doc.select("a[href*=$playUrlPath/]")) {
                val m = playLinkRegex.find(link.attr("href")) ?: continue
                val sId = m.groupValues[1].toIntOrNull() ?: continue
                val ep = m.groupValues[2].toIntOrNull() ?: continue
                bySource.getOrPut(sId) { mutableListOf() }.add(Episode(ep, link.text().trim(), link.attr("href")))
            }
            for ((sId, eps) in bySource) groups.add(EpisodeGroup("線路 $sId", sId, eps.sortedBy { it.number }))
        }
        return groups
    }

    private fun collectEps(links: org.jsoup.select.Elements, regex: Regex): Pair<Int, List<Episode>> {
        val eps = mutableListOf<Episode>(); var sourceId = 0
        for (link in links) {
            val m = regex.find(link.attr("href")) ?: continue
            sourceId = m.groupValues[1].toIntOrNull() ?: continue
            val epNum = m.groupValues[2].toIntOrNull() ?: continue
            eps.add(Episode(epNum, link.text().trim(), link.attr("href")))
        }
        return sourceId to eps
    }
}
```

- [ ] **Step 4: 跑測試確認通過**

Run: `./gradlew :app:testDebugUnitTest --tests "com.gimy.tv.data.scraper.MacCmsEpisodeParserTest"`
Expected: PASS(2 tests)。

- [ ] **Step 5: 基底委派 + Commit**

把 `MacCmsListBasedSource.parseEpisodeGroups` 內容換成 `return MacCmsEpisodeParser.parse(doc, playUrlPath)`。

```bash
./gradlew :app:compileDebugKotlin
git add app/src/main/java/com/gimy/tv/data/scraper/parser/MacCmsEpisodeParser.kt \
        app/src/test/kotlin/com/gimy/tv/data/scraper/MacCmsEpisodeParserTest.kt \
        app/src/main/java/com/gimy/tv/data/scraper/MacCmsListBasedSource.kt
git commit -m "fix: gimy_tw 詳情線路名退化(新增 playlist-mobile 分支)"
```

---

## Phase 5 — 解析感知健康探測機制

### Task 11: SiteSource 新增 probeListCount(預設值)

**Files:**
- Modify: `app/src/main/java/com/gimy/tv/data/scraper/SiteSource.kt`

- [ ] **Step 1: 介面加帶預設值的方法**

```kotlin
interface SiteSource {
    val sourceType: SourceType
    val baseUrl: String

    suspend fun fetchCategories(): List<Category>
    suspend fun fetchVodList(typeId: Int, page: Int): PaginatedResult<Vod>
    suspend fun fetchVodDetail(vodId: Long): VodDetail
    suspend fun fetchPlayerData(episodeUrl: String): PlayerData
    suspend fun search(keyword: String, page: Int): PaginatedResult<Vod>

    /**
     * 對指定 baseUrl 抓「預設分類列表頁第 1 頁」並用本站 parser 解析,回傳解析出的筆數。
     * 健康探測用,與 [getBaseUrl] 解耦(不可走 endpointResolver)。
     * 預設 -1 = 此 source 不支援探測,呼叫端 fallback 至 HEAD 200。
     */
    suspend fun probeListCount(baseUrl: String): Int = -1
}
```

- [ ] **Step 2: 編譯確認**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL(預設實作,既有子類不受影響)。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/gimy/tv/data/scraper/SiteSource.kt
git commit -m "feat: SiteSource 新增 probeListCount 健康探測介面(預設 -1)"
```

### Task 12: 各 source override probeListCount

**Files:**
- Modify: `GimyTvSource.kt`、`GimyMaxSource.kt`、`MacCmsListBasedSource.kt`(涵蓋 gimy_tw/eyny/imaple/momovod/kubo)

預設探測分類:電視劇 typeId=2(各站皆有)。實作用既有 `fetchHtml`+對應 parser,但用傳入的 `baseUrl` 組 URL。

- [ ] **Step 1: GimyTvSource.probeListCount**

```kotlin
    override suspend fun probeListCount(baseUrl: String): Int = withContext(Dispatchers.IO) {
        runCatching {
            val doc = org.jsoup.Jsoup.parse(fetchHtml("$baseUrl/type/2.html"), baseUrl)
            GimyTvParser.parseVodList(doc, baseUrl, 1).items.size
        }.getOrDefault(0)
    }
```

- [ ] **Step 2: GimyMaxSource.probeListCount**

```kotlin
    override suspend fun probeListCount(baseUrl: String): Int = withContext(Dispatchers.IO) {
        runCatching {
            val doc = org.jsoup.Jsoup.parse(fetchHtml("$baseUrl/type/2.html"), baseUrl)
            GimyMaxParser.parseVodList(doc, baseUrl, 1).items.size
        }.getOrDefault(0)
    }
```

- [ ] **Step 3: MacCmsListBasedSource.probeListCount + EynyTvSource override**

基底提供通用版(用 `listUrlPath` + 基底 `parseVodList`);EynyTvSource override 用 `EynyTvParser`。基底:

```kotlin
    override suspend fun probeListCount(baseUrl: String): Int = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$baseUrl$listUrlPath/2.html"
            parseVodList(Jsoup.parse(fetchHtml(url), url), 1).items.size
        }.getOrDefault(0)
    }
```

EynyTvSource:

```kotlin
    override suspend fun probeListCount(baseUrl: String): Int = withContext(Dispatchers.IO) {
        runCatching {
            EynyTvParser.parseVodList(fetchDocument("$baseUrl$listUrlPath/2.html"), baseUrl, 1).items.size
        }.getOrDefault(0)
    }
```

> 若 `parseVodList`/`fetchHtml` 在基底為 private,改 `protected`。

- [ ] **Step 4: 編譯確認**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/gimy/tv/data/scraper/GimyTvSource.kt \
        app/src/main/java/com/gimy/tv/data/scraper/GimyMaxSource.kt \
        app/src/main/java/com/gimy/tv/data/scraper/MacCmsListBasedSource.kt \
        app/src/main/java/com/gimy/tv/data/scraper/EynyTvSource.kt
git commit -m "feat: 各 source 實作 probeListCount(parser-based 健康探測)"
```

### Task 13: Hilt 把 sources 綁進 Map<SourceType, SiteSource>

**Files:**
- Create: `app/src/main/java/com/gimy/tv/di/SourceTypeKey.kt`
- Modify: `app/src/main/java/com/gimy/tv/di/RepositoryModule.kt`

- [ ] **Step 1: 定義 map key**

```kotlin
package com.gimy.tv.di

import com.gimy.tv.domain.model.SourceType
import dagger.MapKey

@MapKey
annotation class SourceTypeKey(val value: SourceType)
```

- [ ] **Step 2: RepositoryModule 加 @IntoMap 綁定(11 source）**

在 `RepositoryModule`(abstract class)加,每個 source 一條。範例 gimytv,其餘比照 SourceType 列舉全列:

```kotlin
    @Binds @IntoMap @SourceTypeKey(SourceType.GIMYTV)
    abstract fun bindGimyTvIntoMap(s: GimyTvSource): SiteSource

    @Binds @IntoMap @SourceTypeKey(SourceType.GIMYMAX)
    abstract fun bindGimyMaxIntoMap(s: GimyMaxSource): SiteSource
    // … MOVIEFFM, GIMY_TW, EYNY_TV, IMAPLE_TV, MOMOVOD, KUBO123, JABLE_TV, XNXX, FORUM5278 各一條
```

需 import：`dagger.multibindings.IntoMap`、`com.gimy.tv.data.scraper.*`、`com.gimy.tv.domain.model.SourceType`。

- [ ] **Step 3: 編譯確認(Hilt 產碼)**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL(Hilt 產生 `Map<SourceType, SiteSource>` 綁定)。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/gimy/tv/di/SourceTypeKey.kt \
        app/src/main/java/com/gimy/tv/di/RepositoryModule.kt
git commit -m "feat: Hilt 多重綁定 Map<SourceType, SiteSource>"
```

### Task 14: EndpointResolver 改 parser-based 探測

**Files:**
- Modify: `app/src/main/java/com/gimy/tv/data/endpoint/EndpointResolver.kt`
- Test: `app/src/test/kotlin/com/gimy/tv/data/endpoint/EndpointHealthTest.kt`

- [ ] **Step 1: 寫健康挑選邏輯的失敗測試**

把候選挑選邏輯抽成純函式 `pickHealthiest(candidates, probe)` 以便測試(probe 為 `suspend (String)->Int`,回 -1 代表不支援)。

```kotlin
package com.gimy.tv.data.endpoint

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EndpointHealthTest {
    @Test fun `picks first candidate with count above threshold by priority`() = runTest {
        val counts = mapOf("a" to 0, "b" to 12, "c" to 20)
        val pick = pickHealthiest(listOf("a", "b", "c"), minItems = 5) { counts.getValue(it) }
        assertThat(pick).isEqualTo("b")  // a 不健康(0),b 是優先序中第一個健康者
    }

    @Test fun `returns null when all candidates unhealthy`() = runTest {
        val pick = pickHealthiest(listOf("a", "b"), minItems = 5) { 0 }
        assertThat(pick).isNull()
    }

    @Test fun `probe returning -1 means unsupported and is skipped not counted healthy`() = runTest {
        val pick = pickHealthiest(listOf("a"), minItems = 5) { -1 }
        assertThat(pick).isNull()
    }
}
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `./gradlew :app:testDebugUnitTest --tests "com.gimy.tv.data.endpoint.EndpointHealthTest"`
Expected: FAIL — `pickHealthiest` 未定義。

- [ ] **Step 3: 實作 pickHealthiest(top-level 函式於 EndpointResolver.kt 同檔)**

```kotlin
/** 純挑選邏輯:在候選中,依優先序回傳第一個 probe 計數 >= minItems 者;全不健康回 null。
 *  probe 回 -1(不支援)視為不健康。抽出以便單元測試。 */
internal suspend fun pickHealthiest(
    candidates: List<String>,
    minItems: Int,
    probe: suspend (String) -> Int,
): String? {
    for (url in candidates) {
        val count = runCatching { probe(url) }.getOrDefault(0)
        if (count >= minItems) return url
    }
    return null
}
```

- [ ] **Step 4: 跑測試確認通過**

Run: `./gradlew :app:testDebugUnitTest --tests "com.gimy.tv.data.endpoint.EndpointHealthTest"`
Expected: PASS(3 tests)。

- [ ] **Step 5: 接線到 EndpointResolver**

- 建構子注入 `private val sourcesProvider: javax.inject.Provider<Map<SourceType, @JvmSuppressWildcards SiteSource>>`(延遲取得破 DI 循環)。
- 新增常數 `private const val MIN_HEALTHY_ITEMS = 5`。
- `refresh()` 內每個 type 改用:

```kotlin
        val newResolved = SourceType.values().associateWith { type ->
            val candidates = (remoteConfig?.get(type) ?: emptyList())
                .ifEmpty { DEFAULTS[type] ?: emptyList() }
            if (candidates.isEmpty()) return@associateWith resolved[type] ?: ""
            val source = sourcesProvider.get()[type]
            val healthy = if (source != null)
                pickHealthiest(candidates, MIN_HEALTHY_ITEMS) { url -> source.probeListCount(url) }
            else null
            // parser 探測選不出(全壞或不支援)→ 回退 HEAD 200 探測 → 再回退首個候選
            healthy ?: pickBestEndpoint(candidates) ?: candidates.first()
        }
```

保留既有 `pickBestEndpoint`(HEAD 探測)當第二層 fallback。`import com.gimy.tv.data.scraper.SiteSource`。

- [ ] **Step 6: 編譯 + 全測試**

Run: `./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL,全部測試 PASS。

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/gimy/tv/data/endpoint/EndpointResolver.kt \
        app/src/test/kotlin/com/gimy/tv/data/endpoint/EndpointHealthTest.kt
git commit -m "feat: EndpointResolver 改 parser-based 健康探測(HEAD 降級為 fallback)"
```

---

## Phase 6 — 收尾

### Task 15: 更新 endpoints.json 預設 + DEFAULTS 同步

**Files:**
- Modify: `endpoints.json`(已改 gimytv/gimymax,確認)
- Modify: `app/src/main/java/com/gimy/tv/data/endpoint/EndpointResolver.kt`(DEFAULTS 硬編 fallback)

- [ ] **Step 1: 確認 endpoints.json**

Run: `grep -A1 'gimytv\|gimymax' endpoints.json`
Expected: gimytv=`https://gimyplus.com`、gimymax=`https://gimy01.co`。

- [ ] **Step 2: 同步 EndpointResolver.DEFAULTS**

把硬編 fallback 改為新網域(冷啟動且遠端不可達時用):

```kotlin
            SourceType.GIMYMAX to listOf("https://gimy01.co"),
            SourceType.GIMYTV to listOf("https://gimyplus.com"),
```

- [ ] **Step 3: 編譯 + 全測試**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全部 PASS。

- [ ] **Step 4: Commit**

```bash
git add endpoints.json app/src/main/java/com/gimy/tv/data/endpoint/EndpointResolver.kt
git commit -m "chore: gimytv/gimymax 新網域(gimyplus.com / gimy01.co)"
```

### Task 16: 整合驗證(手動 smoke)

**Files:** 無(裝置/模擬器驗證)

- [ ] **Step 1: build + 安裝**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 2: 手動驗證清單(裝置)**

逐項確認:
- gimytv / gimymax / eyny_tv 三站列表頁有卡片(非空白)、封面正常顯示
- 點進詳情有線路名(非全是「線路 N」)、集數可列出
- 各站任一集數可成功播放(取流 OK)
- gimy_tw 詳情線路名為真實名稱(如「卧龍雲」)非「線路 N」
- imaple / momovod / kubo123 仍正常(無回歸)

- [ ] **Step 3: 最終 commit(若有手動修正)**

```bash
git add -A && git commit -m "test: 三站 + gimy_tw 整合驗證修正"
```

---

## Self-Review 紀錄

- **Spec coverage**:Part A 四站重寫 → Task 1-10;Part B 健康探測 → Task 11-14;endpoints 更新 → Task 15;測試策略(inline fixture)→ 各 parser task;DI 循環解法 → Task 13-14。皆有對應。
- **取流不測**:`parsePlayerData`/`parsePlayerAaaa` 用 `android.util.Base64`,純 JUnit 不可跑且本次不改 → 不寫測試,靠 Task 16 手動 smoke 驗證,已於風險表記載。
- **型別一致**:parser 物件統一 `parseVodList(doc, baseUrl, page)` / `parseVodDetail(doc, vodId, baseUrl)`;`probeListCount(baseUrl): Int`;`pickHealthiest(candidates, minItems, probe)`。跨 task 命名一致。
- **已知實作期相依**:基底 `fetchHtml`/`fetchDocument`/`parseVodList`/`buildListUrl` 可能需由 private 改 protected(Task 9、12 已標註)。
