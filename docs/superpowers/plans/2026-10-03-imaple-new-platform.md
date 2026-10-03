# 新平台來源重寫（Imaple 合併 eynytv／123kubo）實作計畫

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 讓 eynytv／imaple／123kubo（2026-10-03 同日換成同一套新平台、同一個資料庫）在 App 裡恢復可用，並合併為單一來源 Imaple；順帶修正 slug 對照表的 30 天清理會刪掉收藏對照的既有問題。

**Architecture:** 新增純解析的 `ImapleParser`（卡片 HTML ＋ RSC 內嵌 JSON），改寫 `ImapleTvSource` 實作 `SiteSource`，cuid 字串以既有雜湊轉 `Long` 並寫入 `embed_slugs`。Eyny／Kubo 的 enum 保留但退役：不註冊、不列出、不探測，殘留的舊收藏導向 Imaple，由既有 `SavedEntryRecovery` 以片名找回。

**Tech Stack:** Kotlin、OkHttp、Jsoup、org.json（Android 內建；JVM 測試用 `org.json:json`）、Room、Hilt、JUnit 4 ＋ Truth、sqlite-jdbc（僅測試）。

**Spec:** `docs/superpowers/specs/2026-10-03-imaple-new-platform-design.md`

## Global Constraints

- 程式碼註解用繁體中文，說明「為什麼」與非直觀前提；變數、函式、型別、檔名維持英文。
- commit 訊息用繁體中文、動詞開頭、標題 ≤ 50 字；不加任何 AI 署名；不寫「未測試」「未驗證」之類的旁註。
- 不修改 `SiteSource` 介面。
- 不改任何資料表結構、不新增 Room migration。
- `sqlite-jdbc` 版本 `3.53.4.0`，只能出現在 `testImplementation`。
- `slugStableId` 的演算法一個字都不能改：Jable／XNXX 既有收藏存的就是它的輸出。
- 新爬蟲建立 `Vod` 時必須用 `parseEpisodeStatus()` 填 `siteStatus`。
- 模擬器驗收只用 `emulator-5554`；不得對 USB 實機（`RFCRB126PGM`）做任何安裝或操作。
- `git push` 與更新遠端 `endpoints.json` 必須先徵得使用者同意。
- 每個 commit 之後 `./gradlew testDebugUnitTest` 必須全綠。

## Review Focus

1. 真實頁面的 RSC 片段約 100KB、含上千個跳脫引號：擷取時不可用 `(?:[^"\\]|\\.)*` 這類正則（Java 會逐次遞迴而 StackOverflowError）→ Task 3 加「頁面大小片段」測試。
2. 片名或線路名含 `]`、`}`、`"`：RSC 的括號掃描不可在字串內截斷 → Task 3 加測試。
3. 一部片的線路全是死線（`knownDead`）或代理線：詳情頁應顯示錯誤而不是空的集數清單 → Task 3（解析回空）與 Task 4（組集數時丟錯）各加測試。
4. 搜尋關鍵字含空白、`&`、中文：網址必須正確編碼 → Task 4 加測試。
5. 有篩選參數的分類翻頁（`/series?area=kr` 第 2 頁）：`pg` 要用 `&` 接，不能再接一個 `?` → Task 4 加測試。

---

### Task 1：`embed_slugs` 清理不再刪掉收藏的對照

**Files:**
- Modify: `app/build.gradle.kts`（`testImplementation` 區塊，約第 166–170 行）
- Modify: `app/src/main/java/com/gimy/tv/data/local/dao/Daos.kt`（`EmbedSlugDao`，約第 192–202 行）
- Create: `app/src/test/kotlin/com/gimy/tv/data/local/SlugPruneSqlTest.kt`

**Interfaces:**
- Produces: `internal const val EMBED_SLUG_PRUNE_SQL: String`（package `com.gimy.tv.data.local.dao`），`EmbedSlugDao.pruneOlderThan(thresholdMs: Long): Int` 改用它。Task 4 的 Imaple 會寫入同一張表。

- [ ] **Step 1：加入測試依賴**

在 `app/build.gradle.kts` 的 `testImplementation("org.json:json:20240303")` 下一行加入：

```kotlin
    // 只給清理 SQL 的測試用：在 JVM 上直接執行與 Room DAO 相同的 SQL 字串。
    testImplementation("org.xerial:sqlite-jdbc:3.53.4.0")
```

- [ ] **Step 2：寫會失敗的測試**

建立 `app/src/test/kotlin/com/gimy/tv/data/local/SlugPruneSqlTest.kt`：

```kotlin
package com.gimy.tv.data.local

import com.gimy.tv.data.local.dao.EMBED_SLUG_PRUNE_SQL
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * 啟動清理不能刪掉「收藏或觀看紀錄還在用」的 slug 對照。
 *
 * Jable／XNXX／Imaple 的 vodId 是 slug 的單向雜湊，收藏只存 id；對照只在列表頁寫入，開收藏
 * 不會更新時間戳。清理若只看 cachedAt，30 天沒在列表出現的收藏就永遠打不開（2026-10-03 查證）。
 * 這裡直接執行與 DAO @Query 相同的常數字串，測的就是正式那一句。
 */
class SlugPruneSqlTest {
    private lateinit var db: Connection
    private val threshold = 1_000_000L
    private val old = threshold - 1
    private val fresh = threshold + 1

    @Before fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        exec("CREATE TABLE favorites (vodId INTEGER NOT NULL, sourceType TEXT NOT NULL)")
        exec("CREATE TABLE watch_history (vodId INTEGER NOT NULL, sourceType TEXT NOT NULL)")
        exec(
            "CREATE TABLE embed_slugs (vodId INTEGER NOT NULL, slug TEXT NOT NULL, " +
                "sourceType TEXT NOT NULL, cachedAt INTEGER NOT NULL, PRIMARY KEY(sourceType, vodId))"
        )
    }

    @After fun tearDown() = db.close()

    private fun exec(sql: String) {
        db.createStatement().use { it.execute(sql) }
    }

    private fun slug(vodId: Long, source: String, cachedAt: Long) =
        exec("INSERT INTO embed_slugs VALUES ($vodId, 's$vodId', '$source', $cachedAt)")

    private fun prune(sql: String): Int = db.prepareStatement(sql).use { st ->
        st.setLong(1, threshold)
        st.executeUpdate()
    }

    private fun remaining(table: String): Set<Long> = db.createStatement().use { st ->
        st.executeQuery("SELECT vodId FROM $table").use { rs ->
            buildSet { while (rs.next()) add(rs.getLong(1)) }
        }
    }

    @Test fun `old rows nobody references are pruned`() {
        slug(1, "JABLE_TV", old)
        prune(EMBED_SLUG_PRUNE_SQL)
        assertThat(remaining("embed_slugs")).isEmpty()
    }

    @Test fun `favorited rows survive however old`() {
        slug(2, "JABLE_TV", old)
        exec("INSERT INTO favorites VALUES (2, 'JABLE_TV')")
        prune(EMBED_SLUG_PRUNE_SQL)
        assertThat(remaining("embed_slugs")).containsExactly(2L)
    }

    @Test fun `rows in watch history survive however old`() {
        slug(3, "IMAPLE_TV", old)
        exec("INSERT INTO watch_history VALUES (3, 'IMAPLE_TV')")
        prune(EMBED_SLUG_PRUNE_SQL)
        assertThat(remaining("embed_slugs")).containsExactly(3L)
    }

    @Test fun `fresh rows survive`() {
        slug(4, "XNXX", fresh)
        prune(EMBED_SLUG_PRUNE_SQL)
        assertThat(remaining("embed_slugs")).containsExactly(4L)
    }

    /** 各來源的 id 空間各自獨立：別的來源收藏了同一個數字，不能替這一列擋下清理。 */
    @Test fun `a favorite on another source does not protect this row`() {
        slug(5, "JABLE_TV", old)
        exec("INSERT INTO favorites VALUES (5, 'XNXX')")
        prune(EMBED_SLUG_PRUNE_SQL)
        assertThat(remaining("embed_slugs")).isEmpty()
    }
}
```

- [ ] **Step 3：先把舊 SQL 抽成常數（行為不變），讓測試能編譯**

在 `Daos.kt` 的 `@Dao interface EmbedSlugDao` 上方加入：

```kotlin
/**
 * embed_slugs 的啟動清理。抽成常數是為了讓 SlugPruneSqlTest 執行與 @Query 完全相同的字串。
 */
internal const val EMBED_SLUG_PRUNE_SQL =
    "DELETE FROM embed_slugs WHERE cachedAt < :thresholdMs"
```

並把 `EmbedSlugDao` 的 `@Query("DELETE FROM embed_slugs WHERE cachedAt < :thresholdMs")` 改成 `@Query(EMBED_SLUG_PRUNE_SQL)`。

- [ ] **Step 4：跑測試，確認失敗**

Run: `./gradlew testDebugUnitTest --tests 'com.gimy.tv.data.local.SlugPruneSqlTest'`
Expected: FAIL，`favorited rows survive however old` 與 `rows in watch history survive however old` 兩個案例失敗（實際為空集合）。

- [ ] **Step 5：改成正確的 SQL**

把 Step 3 的常數整段換成：

```kotlin
/**
 * embed_slugs 的啟動清理。被收藏或觀看紀錄引用的列不刪：對照只在列表頁寫入、開收藏只讀不寫，
 * 少了這個條件，30 天沒在列表出現的收藏就再也打不開（2026-10-03 查證）。
 * 抽成常數是為了讓 SlugPruneSqlTest 執行與 @Query 完全相同的字串。
 */
internal const val EMBED_SLUG_PRUNE_SQL =
    "DELETE FROM embed_slugs WHERE cachedAt < :thresholdMs " +
        "AND NOT EXISTS (SELECT 1 FROM favorites f " +
        "WHERE f.vodId = embed_slugs.vodId AND f.sourceType = embed_slugs.sourceType) " +
        "AND NOT EXISTS (SELECT 1 FROM watch_history h " +
        "WHERE h.vodId = embed_slugs.vodId AND h.sourceType = embed_slugs.sourceType)"
```

- [ ] **Step 6：跑測試，確認通過；再跑全部測試**

Run: `./gradlew testDebugUnitTest --tests 'com.gimy.tv.data.local.SlugPruneSqlTest'`
Expected: PASS（5 個案例）
Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL，0 failures（Room 的編譯期 SQL 檢查也會在這一步驗證欄位名稱）

- [ ] **Step 7：Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/gimy/tv/data/local/dao/Daos.kt app/src/test/kotlin/com/gimy/tv/data/local/SlugPruneSqlTest.kt
git commit -m "修正 embed_slugs 清理會刪掉收藏的對照" -m "對照只在列表頁寫入、開收藏不會更新時間戳，啟動清理只看 cachedAt，30 天沒在列表出現的收藏就再也打不開。改為跳過收藏與觀看紀錄還在引用的列。"
```

---

### Task 2：`movieffm_slugs` 清理不再刪掉收藏的對照

**Files:**
- Modify: `app/src/main/java/com/gimy/tv/data/local/dao/Daos.kt`（`MovieffmSlugDao.pruneOlderThan`，約第 184–188 行）
- Modify: `app/src/test/kotlin/com/gimy/tv/data/local/SlugPruneSqlTest.kt`

**Interfaces:**
- Produces: `internal const val MOVIEFFM_SLUG_PRUNE_SQL: String`，`MovieffmSlugDao.pruneOlderThan` 改用它。

- [ ] **Step 1：寫會失敗的測試**

在 `SlugPruneSqlTest` 的 `setUp()` 最後加一張表：

```kotlin
        exec(
            "CREATE TABLE movieffm_slugs (vodId INTEGER PRIMARY KEY NOT NULL, slug TEXT NOT NULL, " +
                "contentType TEXT NOT NULL, cachedAt INTEGER NOT NULL)"
        )
```

在檔案頂端 import 加上 `import com.gimy.tv.data.local.dao.MOVIEFFM_SLUG_PRUNE_SQL`，類別內加入：

```kotlin
    private fun movieffmSlug(vodId: Long, cachedAt: Long) =
        exec("INSERT INTO movieffm_slugs VALUES ($vodId, 'm$vodId', 'movies', $cachedAt)")

    /** MovieFFM 的對照只在列表、搜尋、相關推薦寫入；開收藏只讀不寫，跟 embed_slugs 同一個問題。 */
    @Test fun `movieffm favorites survive pruning`() {
        movieffmSlug(10, old)
        movieffmSlug(11, old)
        movieffmSlug(12, fresh)
        exec("INSERT INTO favorites VALUES (10, 'MOVIEFFM')")
        exec("INSERT INTO watch_history VALUES (11, 'MOVIEFFM')")
        prune(MOVIEFFM_SLUG_PRUNE_SQL)
        assertThat(remaining("movieffm_slugs")).containsExactly(10L, 11L, 12L)
    }

    /** movieffm_slugs 沒有 sourceType 欄位，只能比對 'MOVIEFFM'：別的來源收藏同一個數字不算。 */
    @Test fun `movieffm rows are not protected by other sources`() {
        movieffmSlug(13, old)
        exec("INSERT INTO favorites VALUES (13, 'GIMYTV')")
        prune(MOVIEFFM_SLUG_PRUNE_SQL)
        assertThat(remaining("movieffm_slugs")).isEmpty()
    }
```

在 `Daos.kt` 的 `@Dao interface MovieffmSlugDao` 上方加入（先放舊 SQL，讓測試能編譯）：

```kotlin
/** movieffm_slugs 的啟動清理。抽成常數讓 SlugPruneSqlTest 執行與 @Query 相同的字串。 */
internal const val MOVIEFFM_SLUG_PRUNE_SQL =
    "DELETE FROM movieffm_slugs WHERE cachedAt < :thresholdMs"
```

並把 `MovieffmSlugDao` 的 `@Query("DELETE FROM movieffm_slugs WHERE cachedAt < :thresholdMs")` 改成 `@Query(MOVIEFFM_SLUG_PRUNE_SQL)`。

- [ ] **Step 2：跑測試，確認失敗**

Run: `./gradlew testDebugUnitTest --tests 'com.gimy.tv.data.local.SlugPruneSqlTest'`
Expected: FAIL，`movieffm favorites survive pruning` 失敗（實際只剩 12）。

- [ ] **Step 3：改成正確的 SQL**

```kotlin
/**
 * movieffm_slugs 的啟動清理。被收藏或觀看紀錄引用的列不刪（同 EMBED_SLUG_PRUNE_SQL 的理由）。
 * 這張表沒有 sourceType 欄位，收藏端以 'MOVIEFFM' 比對。
 */
internal const val MOVIEFFM_SLUG_PRUNE_SQL =
    "DELETE FROM movieffm_slugs WHERE cachedAt < :thresholdMs " +
        "AND NOT EXISTS (SELECT 1 FROM favorites f " +
        "WHERE f.vodId = movieffm_slugs.vodId AND f.sourceType = 'MOVIEFFM') " +
        "AND NOT EXISTS (SELECT 1 FROM watch_history h " +
        "WHERE h.vodId = movieffm_slugs.vodId AND h.sourceType = 'MOVIEFFM')"
```

- [ ] **Step 4：跑測試，確認通過；再跑全部測試**

Run: `./gradlew testDebugUnitTest --tests 'com.gimy.tv.data.local.SlugPruneSqlTest'`
Expected: PASS（7 個案例）
Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL，0 failures

- [ ] **Step 5：Commit**

```bash
git add app/src/main/java/com/gimy/tv/data/local/dao/Daos.kt app/src/test/kotlin/com/gimy/tv/data/local/SlugPruneSqlTest.kt
git commit -m "修正 movieffm_slugs 清理會刪掉收藏的對照" -m "與 embed_slugs 相同：開收藏只讀不寫，30 天沒在列表或搜尋出現的收藏會被清掉對照而打不開。"
```

---

### Task 3：新平台解析器 `ImapleParser`

**Files:**
- Create: `app/src/main/java/com/gimy/tv/data/scraper/parser/ImapleParser.kt`
- Create: `app/src/test/kotlin/com/gimy/tv/data/scraper/ImapleParserTest.kt`

**Interfaces:**
- Produces（Task 4 使用）：
  - `internal data class ImapleCard(val cuid: String, val title: String, val coverUrl: String, val status: String)`
  - `internal data class ImapleTitle(val name: String, val coverUrl: String, val remarks: String)`
  - `internal data class ImapleLine(val name: String, val lineKey: String, val episodeNames: List<String>)`
  - `internal data class ImapleMeta(val year: Int, val genre: String, val synopsis: String, val actors: List<String>, val director: String)`
  - `internal object ImapleParser`：`PAGE_SIZE = 28`、`parseCards(doc: Document, baseUrl: String): List<ImapleCard>`、`parseListTotal(doc: Document): Int?`、`parseSearchTotal(doc: Document): Int?`、`extractRsc(html: String): String`、`parseTitle(rsc: String, baseUrl: String): ImapleTitle?`、`parseLines(rsc: String): List<ImapleLine>`、`lineKeyOf(from: String, sourceKey: String): String`、`parseStreamUrl(rsc: String, lineKey: String, episodeNumber: Int): String?`、`parseMeta(doc: Document): ImapleMeta`

- [ ] **Step 1：寫會失敗的測試**

建立 `app/src/test/kotlin/com/gimy/tv/data/scraper/ImapleParserTest.kt`：

```kotlin
package com.gimy.tv.data.scraper

import com.gimy.tv.data.scraper.parser.ImapleParser
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Test

/**
 * eynytv／imaple／123kubo 共用新平台的解析。
 *
 * 片段取自 2026-10-03 實際頁面並截短，保留原本的結構與跳脫方式。
 */
class ImapleParserTest {
    private val base = "https://imaple.tv"

    /** 照站方的寫法把 RSC 內容包成 push 片段；JSONObject.quote 的跳脫與 JSON.stringify 相容。 */
    private fun rscPage(vararg payloads: String): String = buildString {
        append("<html><body>")
        payloads.forEach { append("<script>self.__next_f.push([1,").append(JSONObject.quote(it)).append("])</script>") }
        append("</body></html>")
    }

    private val listHtml = """
        <div><h2>篩選</h2><span class="filter-label shrink-0 text-xs">共 <span class="font-semibold">5,742</span> 部</span></div>
        <a class="navigation-link stub stub--poster" href="/title/cmush6vfu0193qoj6vdva66t2"><div class="stub-poster"><img src="/pi/360/Cd97.webp" alt="多倫多法律與秩序：犯罪傾向 第三季"/></div><div class="stub-tail"><div class="stub-copy"><h3 class="stub-title" title="多倫多法律與秩序：犯罪傾向 第三季">多倫多法律與秩序：犯罪傾向 第三季</h3><p class="stub-meta" title="10集全">10集全</p></div></div></a>
        <a class="navigation-link stub stub--poster stub--rated" href="/title/cmucxzuk100bppihr26ey7d1c"><div class="stub-poster"><img src="/pi/360/qkw.webp" alt="法醫秦明之龍番往事"/></div><div class="stub-tail"><div class="stub-copy"><h3 class="stub-title" title="法醫秦明之龍番往事">法醫秦明之龍番往事</h3><p class="stub-meta" title="更新至第18集">更新至第18集</p></div></div></a>
        <a class="navigation-link stub stub--poster" href="/title/cmucxzuk100bppihr26ey7d1c"><h3 class="stub-title" title="法醫秦明之龍番往事">法醫秦明之龍番往事</h3></a>
        <a class="navigation-link" href="/series?area=kr">韓劇</a>
    """.trimIndent()

    private val searchHitHtml = """
        <p class="mt-6 text-sm text-ink-3">找到 5 筆結果</p>
        <a class="navigation-link stub stub--poster stub--rated" href="/title/cmucxzuk100bppihr26ey7d1c"><h3 class="stub-title" title="法醫秦明之龍番往事">法醫秦明之龍番往事</h3><p class="stub-meta">第18集</p></a>
    """.trimIndent()

    /** 0 筆時頁面下方仍有「你可能會喜歡」的推薦卡片。 */
    private val searchZeroHtml = """
        <p class="mt-6 text-sm text-ink-3">找到 0 筆結果</p>
        <a class="navigation-link stub stub--poster stub--rated" href="/title/cmucxzukb00bspihrwiv788yr"><h3 class="stub-title" title="蘭香如故">蘭香如故</h3></a>
    """.trimIndent()

    private val detailPayload = """{"title":{"id":"cmucxzuk100bppihr26ey7d1c","name":"法醫秦明之龍番往事","pic":"/pi/360/qkw.webp","remarks":"第18集"},"lines":[
        {"from":"非凡資源 · ffm3u8","sourceKey":"cj-ffzyapi-com","sourceLabel":"非凡資源","knownDead":false,"needsProxy":false,"episodes":[{"name":"第01集"},{"name":"第02集"}]},
        {"from":"電影天堂 · dyttm3u8","sourceKey":"cj-dytt","sourceLabel":"電影天堂","knownDead":true,"needsProxy":false,"episodes":[{"name":"第01集"}]},
        {"from":"中轉 · px","sourceKey":"cj-px","sourceLabel":"中轉","knownDead":false,"needsProxy":true,"episodes":[{"name":"第01集"}]},
        {"from":"U酷資源 · ukm3u8","sourceKey":"cj-uku","sourceLabel":"U酷]}資源","knownDead":false,"needsProxy":false,"episodes":[{"name":"第 0 集"},{"name":"第 0 集]"}]}
    ]}"""

    private val playPayload = """{"detail":{"name":"法醫秦明之龍番往事","year":"2026","playGroups":[
        {"from":"非凡資源 · ffm3u8","episodes":[null,null,{"name":"第03集","url":"https://vip.ffzy-plays.com/20260922/58568_e61ca781/index.m3u8"}],"sourceLabel":"非凡資源","sourceKey":"cj-ffzyapi-com"},
        {"from":"如意資源 · rym3u8","episodes":[{"name":"第01集","url":"https://svip.ryiplay18.com/20260922/11456_a982f6d4/index.m3u8"},{"name":"第02集","url":"https://svip.ryiplay18.com/a/2.m3u8"},{"name":"第03集","url":"https://svip.ryiplay18.com/a/3.m3u8"}],"sourceLabel":"如意資源","sourceKey":"cj-rycjapi-com"}
    ]}}"""

    private val jsonLdHtml = """<html><head>
        <meta name="description" content="《法醫秦明之龍番往事》2026 年陸劇。">
        <script type="application/ld+json">[{"@context":"https://schema.org","@type":"TVSeries","name":"法醫秦明之龍番往事","description":"故事發生在九十年代的北方石油小城龍番市。","datePublished":"2026-09-22(中国大陆)","genre":"國產劇","actor":[{"@type":"Person","name":"俞灝明"},{"@type":"Person","name":"史策"}],"director":[{"@type":"Person","name":"邢鍵鈞"}]},{"@type":"BreadcrumbList","itemListElement":[]}]</script>
        </head><body></body></html>"""

    @Test fun `list cards carry cuid title absolute cover and status`() {
        val cards = ImapleParser.parseCards(Jsoup.parse(listHtml, base), base)
        assertThat(cards.map { it.cuid }).containsExactly("cmush6vfu0193qoj6vdva66t2", "cmucxzuk100bppihr26ey7d1c").inOrder()
        assertThat(cards[0].title).isEqualTo("多倫多法律與秩序：犯罪傾向 第三季")
        assertThat(cards[0].coverUrl).isEqualTo("https://imaple.tv/pi/360/Cd97.webp")
        assertThat(cards[1].status).isEqualTo("更新至第18集")
    }

    @Test fun `list total reads the number with a thousands separator`() {
        assertThat(ImapleParser.parseListTotal(Jsoup.parse(listHtml, base))).isEqualTo(5742)
    }

    @Test fun `search total reads the found count`() {
        assertThat(ImapleParser.parseSearchTotal(Jsoup.parse(searchHitHtml, base))).isEqualTo(5)
    }

    /** 陷阱本身要被釘住：0 筆的頁面上確實解析得出卡片，所以來源必須先看筆數。 */
    @Test fun `zero-result search still shows recommendation cards`() {
        val doc = Jsoup.parse(searchZeroHtml, base)
        assertThat(ImapleParser.parseSearchTotal(doc)).isEqualTo(0)
        assertThat(ImapleParser.parseCards(doc, base)).isNotEmpty()
    }

    @Test fun `rsc fragments are decoded and joined in order`() {
        assertThat(ImapleParser.extractRsc(rscPage("ab\"c", "d\\e"))).isEqualTo("ab\"cd\\e")
    }

    /** 站上實際的跳脫寫法（\" 包住每個 key 與值），原樣貼進來驗證。 */
    @Test fun `a real escaped fragment from the site decodes`() {
        val html = """<script>self.__next_f.push([1,"\"title\":{\"id\":\"cmucxzuk100bppihr26ey7d1c\",\"name\":\"法醫秦明之龍番往事\",\"pic\":\"/pi/360/qkw.webp\",\"remarks\":\"第18集\"}"])</script>"""
        val title = ImapleParser.parseTitle(ImapleParser.extractRsc(html), base)
        assertThat(title?.name).isEqualTo("法醫秦明之龍番往事")
        assertThat(title?.coverUrl).isEqualTo("https://imaple.tv/pi/360/qkw.webp")
        assertThat(title?.remarks).isEqualTo("第18集")
    }

    /** 真實頁面的片段約 100KB、上千個跳脫引號；用遞迴式正則擷取會 StackOverflowError。 */
    @Test fun `a page-sized fragment with thousands of escaped quotes is extracted`() {
        val big = (1..5000).joinToString(",", "[", "]") { """{"name":"第${it}集"}""" }
        assertThat(ImapleParser.extractRsc(rscPage(big))).isEqualTo(big)
    }

    @Test fun `title picks the object that starts with id, not other title keys`() {
        val rsc = """{"metadata":{"title":"Imaple 楓林網"},"title":{"id":"x1","name":"真片名","pic":"","remarks":""}}"""
        assertThat(ImapleParser.parseTitle(rsc, base)?.name).isEqualTo("真片名")
    }

    @Test fun `lines drop dead and proxy-only routes`() {
        val lines = ImapleParser.parseLines(detailPayload)
        assertThat(lines.map { it.lineKey }).containsExactly("cj-ffzyapi-com.ffm3u8", "cj-uku.ukm3u8").inOrder()
        assertThat(lines[0].name).isEqualTo("非凡資源")
        assertThat(lines[0].episodeNames).containsExactly("第01集", "第02集").inOrder()
    }

    /** 名稱裡的 ] } 若被當成結構符號，整個 lines 陣列會在錯的位置截斷。 */
    @Test fun `brackets inside names do not cut the lines array short`() {
        val uku = ImapleParser.parseLines(detailPayload).single { it.lineKey == "cj-uku.ukm3u8" }
        assertThat(uku.name).isEqualTo("U酷]}資源")
        assertThat(uku.episodeNames).containsExactly("第 0 集", "第 0 集]").inOrder()
    }

    @Test fun `a title whose routes are all dead has no lines`() {
        val rsc = """{"lines":[{"from":"A · a","sourceKey":"k","knownDead":true,"episodes":[{"name":"第01集"}]}]}"""
        assertThat(ImapleParser.parseLines(rsc)).isEmpty()
    }

    @Test fun `line key joins sourceKey and the format code after the middle dot`() {
        assertThat(ImapleParser.lineKeyOf("非凡資源 · ffm3u8", "cj-ffzyapi-com")).isEqualTo("cj-ffzyapi-com.ffm3u8")
        assertThat(ImapleParser.lineKeyOf("沒有格式代碼", "k")).isEqualTo("k")
    }

    @Test fun `stream url comes from the requested line and episode position`() {
        assertThat(ImapleParser.parseStreamUrl(playPayload, "cj-rycjapi-com.rym3u8", 3))
            .isEqualTo("https://svip.ryiplay18.com/a/3.m3u8")
        assertThat(ImapleParser.parseStreamUrl(playPayload, "cj-ffzyapi-com.ffm3u8", 3))
            .isEqualTo("https://vip.ffzy-plays.com/20260922/58568_e61ca781/index.m3u8")
    }

    /** 非指定線路只帶鄰近幾集、其餘是 null；拿不到就要回 null，讓上層丟錯換線。 */
    @Test fun `an episode the page did not include yields no url`() {
        assertThat(ImapleParser.parseStreamUrl(playPayload, "cj-ffzyapi-com.ffm3u8", 1)).isNull()
        assertThat(ImapleParser.parseStreamUrl(playPayload, "cj-rycjapi-com.rym3u8", 9)).isNull()
        assertThat(ImapleParser.parseStreamUrl(playPayload, "no-such-line", 1)).isNull()
    }

    @Test fun `meta reads year genre actors director and synopsis from the JSON-LD array`() {
        val meta = ImapleParser.parseMeta(Jsoup.parse(jsonLdHtml, base))
        assertThat(meta.year).isEqualTo(2026)
        assertThat(meta.genre).isEqualTo("國產劇")
        assertThat(meta.actors).containsExactly("俞灝明", "史策").inOrder()
        assertThat(meta.director).isEqualTo("邢鍵鈞")
        assertThat(meta.synopsis).isEqualTo("故事發生在九十年代的北方石油小城龍番市。")
    }

    @Test fun `meta falls back to the meta description without JSON-LD`() {
        val html = """<html><head><meta name="description" content="只有簡介"></head><body></body></html>"""
        val meta = ImapleParser.parseMeta(Jsoup.parse(html, base))
        assertThat(meta.synopsis).isEqualTo("只有簡介")
        assertThat(meta.year).isEqualTo(0)
    }
}
```

- [ ] **Step 2：跑測試，確認失敗**

Run: `./gradlew testDebugUnitTest --tests 'com.gimy.tv.data.scraper.ImapleParserTest'`
Expected: FAIL，編譯錯誤 `Unresolved reference: ImapleParser`

- [ ] **Step 3：實作 `ImapleParser`**

建立 `app/src/main/java/com/gimy/tv/data/scraper/parser/ImapleParser.kt`：

```kotlin
package com.gimy.tv.data.scraper.parser

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.nodes.Document

/** 列表／搜尋卡片。 */
internal data class ImapleCard(val cuid: String, val title: String, val coverUrl: String, val status: String)

/** 詳情頁 RSC 的 title 物件。 */
internal data class ImapleTitle(val name: String, val coverUrl: String, val remarks: String)

/** 一條可播的線路。[lineKey] 是播放網址 `?line=` 的值。 */
internal data class ImapleLine(val name: String, val lineKey: String, val episodeNames: List<String>)

/** 詳情頁 JSON-LD 的中繼資料；欄位缺少時給空值，不讓整頁失敗。 */
internal data class ImapleMeta(
    val year: Int = 0,
    val genre: String = "",
    val synopsis: String = "",
    val actors: List<String> = emptyList(),
    val director: String = "",
)

/**
 * eynytv／imaple／123kubo 共用的新平台（Next.js App Router）解析。
 *
 * 只做解析、不碰網路，才能用真實頁面在 JVM 上測。列表與搜尋讀卡片 HTML；線路、集數、
 * 播放網址只存在頁面內嵌的 RSC 資料（self.__next_f.push 片段），只能從那裡讀。兩者分開解析，
 * 站方只改其中一種格式時，另一半照樣能用。
 */
internal object ImapleParser {
    const val PAGE_SIZE = 28

    private const val PUSH_OPEN = "self.__next_f.push([1,\""
    private val LIST_TOTAL = Regex("""共\s*([\d,]+)\s*部""")
    private val SEARCH_TOTAL = Regex("""找到\s*([\d,]+)\s*筆""")
    private val YEAR = Regex("""\d{4}""")

    fun parseCards(doc: Document, baseUrl: String): List<ImapleCard> =
        doc.select("a.stub[href^=/title/]").mapNotNull { a ->
            val cuid = a.attr("href").removePrefix("/title/").substringBefore('?').substringBefore('/')
            if (cuid.isBlank()) return@mapNotNull null
            val img = a.selectFirst("img")
            val title = a.selectFirst("h3.stub-title")?.attr("title")?.trim()?.takeIf { it.isNotEmpty() }
                ?: img?.attr("alt")?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            ImapleCard(
                cuid = cuid,
                title = title,
                coverUrl = absolutize(img?.attr("src").orEmpty(), baseUrl),
                status = a.selectFirst("p.stub-meta")?.text()?.trim().orEmpty(),
            )
        }.distinctBy { it.cuid }

    fun parseListTotal(doc: Document): Int? = firstNumber(LIST_TOTAL, doc)

    fun parseSearchTotal(doc: Document): Int? = firstNumber(SEARCH_TOTAL, doc)

    private fun firstNumber(re: Regex, doc: Document): Int? =
        re.find(doc.body()?.text().orEmpty())?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()

    /**
     * 把所有 RSC 片段解跳脫後依序串起來。
     *
     * 刻意逐字元找結尾引號、不用 `(?:[^"\\]|\\.)*` 這類正則：真實片段約 100KB、上千個跳脫引號，
     * Java 的正則每重複一次就遞迴一層，會在大頁面上 StackOverflowError。
     * 片段是 JSON 相容的字串字面值（站方以 JSON.stringify 產生），解跳脫交給 JSONTokener。
     */
    fun extractRsc(html: String): String {
        val out = StringBuilder()
        var from = 0
        while (true) {
            val open = html.indexOf(PUSH_OPEN, from)
            if (open < 0) break
            val start = open + PUSH_OPEN.length
            val end = closingQuote(html, start)
            if (end < 0) break
            runCatching { JSONTokener(html.substring(start - 1, end + 1)).nextValue() as String }
                .onSuccess { out.append(it) }
            from = end + 1
        }
        return out.toString()
    }

    private fun closingQuote(s: String, start: Int): Int {
        var i = start
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i += 2
                '"' -> return i
                else -> i++
            }
        }
        return -1
    }

    fun parseTitle(rsc: String, baseUrl: String): ImapleTitle? {
        // RSC 裡 "title" 這個 key 不只一處（例如頁面 metadata），真正的片子物件以 "id" 開頭。
        val obj = jsonAfter(rsc, "title", '{') { it.startsWith("{\"id\"") }
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val name = obj.optString("name").trim().takeIf { it.isNotEmpty() } ?: return null
        return ImapleTitle(name, absolutize(obj.optString("pic"), baseUrl), obj.optString("remarks").trim())
    }

    fun parseLines(rsc: String): List<ImapleLine> {
        val arr = jsonAfter(rsc, "lines", '[')?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            // 站方標記已死的線路不給選；需要站方代理才能播的線路行為未驗證，先不提供。
            if (o.optBoolean("knownDead") || o.optBoolean("needsProxy")) return@mapNotNull null
            val sourceKey = o.optString("sourceKey").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val from = o.optString("from")
            val eps = o.optJSONArray("episodes") ?: return@mapNotNull null
            val names = (0 until eps.length()).map { j -> eps.optJSONObject(j)?.optString("name").orEmpty() }
            if (names.isEmpty()) return@mapNotNull null
            val label = o.optString("sourceLabel").trim().ifEmpty { from.substringBefore('·').trim() }
            ImapleLine(label, lineKeyOf(from, sourceKey), names)
        }
    }

    /** `?line=` 的值：sourceKey 加上 from 裡「·」（U+00B7）後的格式代碼，例如 cj-ffzyapi-com.ffm3u8。 */
    fun lineKeyOf(from: String, sourceKey: String): String {
        val format = from.substringAfter('·', "").trim()
        return if (format.isEmpty()) sourceKey else "$sourceKey.$format"
    }

    /**
     * 指定線路、第 [episodeNumber] 集（從 1 起）的 m3u8；沒有就回 null。
     *
     * 只有網址 `?line=` 指定的那條線路會帶全部集數的 url，其他線路只帶鄰近幾集、其餘是 null，
     * 所以一定要用「該線路、該集」的播放頁請求，不能拿別條線路的頁面來找。
     */
    fun parseStreamUrl(rsc: String, lineKey: String, episodeNumber: Int): String? {
        val groups = jsonAfter(rsc, "playGroups", '[')?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?: return null
        for (i in 0 until groups.length()) {
            val g = groups.optJSONObject(i) ?: continue
            if (lineKeyOf(g.optString("from"), g.optString("sourceKey")) != lineKey) continue
            val ep = g.optJSONArray("episodes")?.optJSONObject(episodeNumber - 1) ?: return null
            return ep.optString("url").trim().takeIf { it.startsWith("http") }
        }
        return null
    }

    fun parseMeta(doc: Document): ImapleMeta {
        val fallbackSynopsis = doc.selectFirst("meta[name=description]")?.attr("content")?.trim().orEmpty()
        val item = doc.select("script[type=application/ld+json]").asSequence()
            .mapNotNull { runCatching { JSONTokener(it.data()).nextValue() }.getOrNull() }
            .flatMap { v ->
                when (v) {
                    is JSONArray -> (0 until v.length()).mapNotNull { v.optJSONObject(it) }.asSequence()
                    is JSONObject -> sequenceOf(v)
                    else -> emptySequence()
                }
            }
            .firstOrNull { it.has("datePublished") || it.has("actor") }
            ?: return ImapleMeta(synopsis = fallbackSynopsis)
        return ImapleMeta(
            year = YEAR.find(item.optString("datePublished"))?.value?.toIntOrNull() ?: 0,
            genre = strings(item.opt("genre")).joinToString(" / "),
            synopsis = item.optString("description").trim().ifEmpty { fallbackSynopsis },
            actors = personNames(item.opt("actor")),
            director = personNames(item.opt("director")).joinToString(" / "),
        )
    }

    private fun strings(v: Any?): List<String> = when (v) {
        is String -> listOf(v.trim()).filter { it.isNotEmpty() }
        is JSONArray -> (0 until v.length()).mapNotNull { v.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
        else -> emptyList()
    }

    private fun personNames(v: Any?): List<String> = when (v) {
        is JSONArray -> (0 until v.length()).mapNotNull { i ->
            (v.optJSONObject(i)?.optString("name") ?: v.optString(i)).trim().takeIf { it.isNotEmpty() }
        }
        is JSONObject -> listOfNotNull(v.optString("name").trim().takeIf { it.isNotEmpty() })
        is String -> listOf(v.trim()).filter { it.isNotEmpty() }
        else -> emptyList()
    }

    /**
     * 找出 `"key":` 後面緊接著、以 [open] 開頭的 JSON 值，回傳完整原始字串。
     *
     * 逐字元掃描並追蹤「是否在字串內」：片名或線路名裡的 ] } 不能被當成結構符號，否則會在
     * 錯的位置截斷。同一個 key 可能出現多次，[accept] 用來挑出正確的那一個。
     */
    internal fun jsonAfter(text: String, key: String, open: Char, accept: (String) -> Boolean = { true }): String? {
        val needle = "\"$key\":"
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) return null
            val start = at + needle.length
            if (start < text.length && text[start] == open) {
                val end = matchingEnd(text, start)
                if (end > start) {
                    val value = text.substring(start, end + 1)
                    if (accept(value)) return value
                }
            }
            from = start
        }
    }

    private fun matchingEnd(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun absolutize(url: String, baseUrl: String): String = when {
        url.isBlank() -> ""
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> baseUrl.trimEnd('/') + url
        else -> url
    }
}
```

- [ ] **Step 4：跑測試，確認通過；再跑全部測試**

Run: `./gradlew testDebugUnitTest --tests 'com.gimy.tv.data.scraper.ImapleParserTest'`
Expected: PASS（16 個案例）
Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL，0 failures

- [ ] **Step 5：Commit**

```bash
git add app/src/main/java/com/gimy/tv/data/scraper/parser/ImapleParser.kt app/src/test/kotlin/com/gimy/tv/data/scraper/ImapleParserTest.kt
git commit -m "新增新平台解析器 ImapleParser" -m "eynytv、imaple、123kubo 換成同一套 Next.js 新平台：列表與搜尋讀卡片 HTML，線路、集數與播放網址只存在頁面內嵌的 RSC 資料，分開解析讓單邊改版不拖垮另一邊。"
```

---

### Task 4：`ImapleTvSource` 改接新平台

**Files:**
- Modify: `app/src/main/java/com/gimy/tv/data/scraper/EmbedSlugResolution.kt`（新增 `slugStableId`）
- Modify: `app/src/main/java/com/gimy/tv/data/scraper/EmbeddedHlsSource.kt`（`stableId` 改用 `slugStableId`，刪除私有 `stableHashLong`，約第 75–80、109–114 行）
- Modify: `app/src/main/java/com/gimy/tv/domain/model/SiteCategoryMap.kt`（`SourceType.IMAPLE_TV` 項目，約第 138–143 行）
- Rewrite: `app/src/main/java/com/gimy/tv/data/scraper/ImapleTvSource.kt`
- Create: `app/src/test/kotlin/com/gimy/tv/data/scraper/ImapleTvSourceHelpersTest.kt`

**Interfaces:**
- Consumes: Task 3 的 `ImapleParser`、`ImapleCard`、`ImapleLine`；既有 `resolveSlug(vodId, fromMemory, fromDb, remember)`、`EmbedSlugDao.getSlug(vodId, sourceType)`、`EmbedSlugDao.insertAll(rows)`、`OkHttpClient.fetchHtml(url, userAgent?, referer?, budgetMs?)`、`OkHttpClient.fetchDocument(...)`、`remainingBudget(deadlineMs)`、`parseEpisodeStatus(raw)`。
- Produces：
  - `internal fun slugStableId(slug: String): Long`（`EmbedSlugResolution.kt`）
  - `class ImapleTvSource @Inject constructor(client: OkHttpClient, endpointResolver: EndpointResolver, embedSlugDao: EmbedSlugDao) : SiteSource`
  - 同檔頂層：`internal fun cuidOrThrow(vodId: Long, cuid: String?): String`、`internal val IMAPLE_CATEGORY_PATHS: Map<StandardCategory, String>`、`internal fun imapleListUrl(baseUrl: String, typeId: Int, page: Int): String`、`internal fun imapleSearchUrl(baseUrl: String, keyword: String, page: Int): String`、`internal data class ImaplePlay(val cuid: String, val episode: Int, val lineKey: String)`、`internal fun imaplePlayUrl(cuid: String, episode: Int, lineKey: String): String`、`internal fun parseImaplePlayUrl(url: String): ImaplePlay?`、`internal fun imapleEpisodeGroups(cuid: String, lines: List<ImapleLine>): List<EpisodeGroup>`

- [ ] **Step 1：寫會失敗的測試**

建立 `app/src/test/kotlin/com/gimy/tv/data/scraper/ImapleTvSourceHelpersTest.kt`：

```kotlin
package com.gimy.tv.data.scraper

import com.gimy.tv.data.scraper.parser.ImapleLine
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.StandardCategory
import com.gimy.tv.domain.model.categoryMap
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/**
 * ImapleTvSource 中與網路無關的規則。來源本身要 OkHttp 與 EndpointResolver（需要 Android Context）
 * 才建得起來，所以會出錯的判斷都抽成頂層函式在這裡測。
 */
class ImapleTvSourceHelpersTest {
    private val base = "https://imaple.tv"
    private val map = SourceType.IMAPLE_TV.categoryMap

    /** 期望值以搬移前的演算法算出；改了就是 Jable／XNXX／Imaple 的既有收藏全部對不回 slug。 */
    @Test fun `stable id algorithm is unchanged`() {
        assertThat(slugStableId("ssis-001")).isEqualTo(4324561783251707987L)
        assertThat(slugStableId("cmucxzuk100bppihr26ey7d1c")).isEqualTo(766216315209902468L)
    }

    /** DetailViewModel 只對非網路錯誤呼叫 SavedEntryRecovery；丟成 IOException，舊收藏就永遠救不回來。 */
    @Test fun `a missing cuid is a scraper error, not an io error`() {
        val e = assertThrows(ScraperException::class.java) { cuidOrThrow(42L, null) }
        assertThat(e).isNotInstanceOf(IOException::class.java)
        assertThat(cuidOrThrow(42L, "c1")).isEqualTo("c1")
    }

    @Test fun `every standard category except adult has a path`() {
        StandardCategory.values().filter { it != StandardCategory.ADULT }.forEach { cat ->
            assertThat(imapleListUrl(base, map.typeIdFor(cat), 1)).startsWith("$base/")
        }
    }

    /** 已經帶篩選參數的路徑，翻頁要用 & 接，不能再接一個 ?。 */
    @Test fun `filtered category pages append pg with an ampersand`() {
        assertThat(imapleListUrl(base, map.typeIdFor(StandardCategory.KOREAN), 2)).isEqualTo("$base/series?area=kr&pg=2")
        assertThat(imapleListUrl(base, map.typeIdFor(StandardCategory.ANIME), 1)).isEqualTo("$base/anime?pg=1")
    }

    @Test fun `an unknown type id is rejected`() {
        assertThrows(ScraperException::class.java) { imapleListUrl(base, 99, 1) }
    }

    @Test fun `search keywords are trimmed and url encoded`() {
        assertThat(imapleSearchUrl(base, " 法醫 秦明&1 ", 2))
            .isEqualTo("$base/search?wd=%E6%B3%95%E9%86%AB+%E7%A7%A6%E6%98%8E%261&pg=2")
    }

    @Test fun `play url round trips the line key`() {
        val url = imaplePlayUrl("cmucxzuk100bppihr26ey7d1c", 3, "cj-ffzyapi-com.ffm3u8")
        assertThat(url).isEqualTo("/play/cmucxzuk100bppihr26ey7d1c/3?line=cj-ffzyapi-com.ffm3u8")
        assertThat(parseImaplePlayUrl(url)).isEqualTo(ImaplePlay("cmucxzuk100bppihr26ey7d1c", 3, "cj-ffzyapi-com.ffm3u8"))
    }

    @Test fun `an old maccms play url is not mistaken for a new one`() {
        assertThat(parseImaplePlayUrl("/vodplay/123-1-1.html")).isNull()
    }

    /** 有些來源的集名是「第 0 集」：集數一律依位置，名稱錯成 0 的改成位置編號。 */
    @Test fun `episodes are numbered by position and zero-numbered names are replaced`() {
        val groups = imapleEpisodeGroups(
            "c1",
            listOf(
                ImapleLine("非凡資源", "a.ffm3u8", listOf("第01集", "第02集")),
                ImapleLine("U酷資源", "b.ukm3u8", listOf("第 0 集", "第 0 集")),
            ),
        )
        assertThat(groups.map { it.sourceId }).containsExactly(1, 2).inOrder()
        assertThat(groups[1].episodes.map { it.number }).containsExactly(1, 2).inOrder()
        assertThat(groups[1].episodes.map { it.title }).containsExactly("第01集", "第02集").inOrder()
        assertThat(groups[1].episodes[1].playUrl).isEqualTo("/play/c1/2?line=b.ukm3u8")
    }

    @Test fun `a title without playable lines is an error, not an empty list`() {
        assertThrows(ScraperException::class.java) { imapleEpisodeGroups("c1", emptyList()) }
    }
}
```

- [ ] **Step 2：跑測試，確認失敗**

Run: `./gradlew testDebugUnitTest --tests 'com.gimy.tv.data.scraper.ImapleTvSourceHelpersTest'`
Expected: FAIL，編譯錯誤 `Unresolved reference: slugStableId`（以及其他尚未建立的函式）

- [ ] **Step 3：把雜湊函式搬到共用位置**

在 `EmbedSlugResolution.kt` 檔尾加入：

```kotlin
/**
 * slug（或 cuid）→ 穩定的非負 Long id。
 *
 * 演算法一個字都不能改：Jable／XNXX／Imaple 的收藏與觀看紀錄只存這個 id，改了就全部對不回 slug。
 */
internal fun slugStableId(slug: String): Long {
    var h = 1125899906842597L
    for (c in slug) h = 31L * h + c.code.toLong()
    return h and 0x7FFFFFFFFFFFFFFFL  // 去掉符號位，Vod.id 才不會是負數
}
```

在 `EmbeddedHlsSource.kt`：
- 把 `stableId()` 內的 `val id = stableHashLong(slug)` 改成 `val id = slugStableId(slug)`。
- 刪除整個 `private fun stableHashLong(s: String): Long { … }`（約第 109–114 行）。

- [ ] **Step 4：改寫 IMAPLE_TV 的分類代碼**

在 `SiteCategoryMap.kt` 把 `SourceType.IMAPLE_TV -> SiteCategoryMap( … )` 整段換成：

```kotlin
    // 2026-10-03 起是新平台（路徑式分類，見 ImapleTvSource.kt 的 IMAPLE_CATEGORY_PATHS）。
    // 這些 typeId 只是 App 內部代碼、不對應站上任何數字；categoryFor 靠它們反查，所以不可重複。
    // 新平台沒有成人分類。
    SourceType.IMAPLE_TV -> SiteCategoryMap(
        movie = 1, series = 2, variety = 3, anime = 4,
        korean = 5, chinese = 6, hk = 7, taiwan = 8,
        japanese = 9, american = 10, documentary = 11,
    )
```

- [ ] **Step 5：改寫 `ImapleTvSource`**

把 `app/src/main/java/com/gimy/tv/data/scraper/ImapleTvSource.kt` 整個檔案換成：

```kotlin
package com.gimy.tv.data.scraper

import com.gimy.tv.data.cache.TtlLruCache
import com.gimy.tv.data.endpoint.EndpointResolver
import com.gimy.tv.data.local.dao.EmbedSlugDao
import com.gimy.tv.data.local.entity.EmbedSlugEntity
import com.gimy.tv.data.scraper.parser.ImapleCard
import com.gimy.tv.data.scraper.parser.ImapleLine
import com.gimy.tv.data.scraper.parser.ImapleParser
import com.gimy.tv.domain.model.Episode
import com.gimy.tv.domain.model.EpisodeGroup
import com.gimy.tv.domain.model.PaginatedResult
import com.gimy.tv.domain.model.PlayerData
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.StandardCategory
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.domain.model.VodDetail
import com.gimy.tv.domain.model.categoryMap
import com.gimy.tv.domain.util.parseEpisodeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject

/**
 * Imaple 來源。2026-10-03 起 eynytv／imaple／123kubo 是同一套新平台、同一個資料庫，
 * 合併成這一個來源，三個網域由 EndpointResolver 互為備援。
 *
 * 新平台的 id 是 cuid 字串，Vod.id 卻必須是 Long：比照 Jable／XNXX，把 cuid 雜湊成 Long，
 * 對照寫進 embed_slugs——收藏只存 id，process 重啟後要靠它找回 cuid。
 */
class ImapleTvSource @Inject constructor(
    private val client: OkHttpClient,
    private val endpointResolver: EndpointResolver,
    private val embedSlugDao: EmbedSlugDao,
) : SiteSource {
    override val sourceType = SourceType.IMAPLE_TV
    override val baseUrl: String get() = endpointResolver.getBaseUrl(sourceType)

    private val idToCuid = TtlLruCache<Long, String>(CUID_CACHE_CAPACITY)

    override suspend fun fetchVodList(typeId: Int, page: Int): PaginatedResult<Vod> =
        withContext(Dispatchers.IO) {
            val doc = client.fetchDocument(imapleListUrl(baseUrl, typeId, page))
            val cards = ImapleParser.parseCards(doc, baseUrl)
            val total = ImapleParser.parseListTotal(doc)
            // 有總數卻一張卡片都沒有＝版型變了。丟錯才會被健康偵測標成失效，而不是安靜地顯示空白。
            if (total != null && total > 0 && cards.isEmpty()) {
                throw ScraperException("Imaple 版型變更：共 $total 部卻解析不到卡片")
            }
            toPage(cards, page, total)
        }

    override suspend fun search(keyword: String, page: Int): PaginatedResult<Vod> =
        withContext(Dispatchers.IO) {
            val doc = client.fetchDocument(imapleSearchUrl(baseUrl, keyword, page))
            // 0 筆時頁面下方仍有「你可能會喜歡」卡片，不先讀筆數就會把推薦當成搜尋結果。
            val total = ImapleParser.parseSearchTotal(doc)
                ?: throw ScraperException("Imaple 版型變更：搜尋頁找不到結果筆數")
            if (total == 0) return@withContext PaginatedResult(emptyList(), page, 0, false)
            toPage(ImapleParser.parseCards(doc, baseUrl), page, total)
        }

    override suspend fun fetchVodDetail(vodId: Long): VodDetail = withContext(Dispatchers.IO) {
        val cuid = cuidOrThrow(
            vodId,
            resolveSlug(
                vodId = vodId,
                fromMemory = { idToCuid.get(it) },
                fromDb = { embedSlugDao.getSlug(it, sourceType.name) },
                remember = { id, cuid -> idToCuid.put(id, cuid) },
            ),
        )
        val html = client.fetchHtml("$baseUrl/title/$cuid")
        val rsc = ImapleParser.extractRsc(html)
        val title = ImapleParser.parseTitle(rsc, baseUrl)
            ?: throw ScraperException("Imaple 版型變更：詳情頁找不到片名")
        val meta = ImapleParser.parseMeta(Jsoup.parse(html, baseUrl))
        VodDetail(
            vod = Vod(
                vodId, sourceType, title.name, title.coverUrl, meta.genre, meta.year, title.remarks,
                siteStatus = parseEpisodeStatus(title.remarks),
            ),
            director = meta.director,
            actors = meta.actors,
            synopsis = meta.synopsis,
            episodes = imapleEpisodeGroups(cuid, ImapleParser.parseLines(rsc)),
        )
    }

    override suspend fun fetchPlayerData(episodeUrl: String, deadlineMs: Long?): PlayerData =
        withContext(Dispatchers.IO) {
            val play = parseImaplePlayUrl(episodeUrl)
                ?: throw ScraperException("不是 Imaple 的播放網址：$episodeUrl")
            val html = client.fetchHtml("$baseUrl$episodeUrl", budgetMs = remainingBudget(deadlineMs))
            // 拿不到就丟錯：播放器既有的邏輯會自動換下一條線路。
            val url = ImapleParser.parseStreamUrl(ImapleParser.extractRsc(html), play.lineKey, play.episode)
                ?: throw ScraperException("此線路沒有第 ${play.episode} 集的播放網址")
            PlayerData(url, 0, play.lineKey)
        }

    override suspend fun probeListCount(baseUrl: String, profile: String?): Int = withContext(Dispatchers.IO) {
        runCatching { ImapleParser.parseCards(client.fetchDocument("$baseUrl/series"), baseUrl).size }
            .getOrDefault(0)
    }

    private suspend fun toPage(cards: List<ImapleCard>, page: Int, total: Int?): PaginatedResult<Vod> {
        val withIds = cards.map { slugStableId(it.cuid) to it }
        withIds.forEach { (id, card) -> idToCuid.put(id, card.cuid) }
        // 收藏只存 id；對照要落地，process 重啟後才找得回 cuid。寫入失敗不影響這次顯示。
        runCatching {
            embedSlugDao.insertAll(withIds.map { (id, card) -> EmbedSlugEntity(id, card.cuid, sourceType.name) })
        }
        val vods = withIds.map { (id, card) ->
            Vod(id, sourceType, card.title, card.coverUrl, "", 0, card.status, siteStatus = parseEpisodeStatus(card.status))
        }
        val size = ImapleParser.PAGE_SIZE
        val totalPages = total?.let { (it + size - 1) / size } ?: page
        return PaginatedResult(vods, page, totalPages, hasMore = total != null && page * size < total)
    }

    private companion object {
        const val CUID_CACHE_CAPACITY = 2000
    }
}

/**
 * 找不到 cuid 時丟 [ScraperException]——不可是 IOException：DetailViewModel 只對非網路錯誤呼叫
 * SavedEntryRecovery 以片名找回，丟錯型別的話改版前的舊收藏就永遠救不回來。
 */
internal fun cuidOrThrow(vodId: Long, cuid: String?): String =
    cuid ?: throw ScraperException("找不到 Imaple 影片 $vodId 的對照，可能是改版前的舊紀錄")

/** 標準分類 → 新平台路徑。typeId 先經 SiteCategoryMap 的 IMAPLE_TV 代碼轉成分類。 */
internal val IMAPLE_CATEGORY_PATHS: Map<StandardCategory, String> = mapOf(
    StandardCategory.MOVIE to "/movie",
    StandardCategory.SERIES to "/series",
    StandardCategory.ANIME to "/anime",
    StandardCategory.VARIETY to "/variety",
    StandardCategory.KOREAN to "/series?area=kr",
    StandardCategory.CHINESE to "/series?area=cn",
    StandardCategory.HK to "/series?area=hk",
    StandardCategory.TAIWAN to "/series?area=tw",
    StandardCategory.JAPANESE to "/series?area=jp",
    StandardCategory.AMERICAN to "/series?area=west",
    StandardCategory.DOCUMENTARY to "/doc",
)

internal fun imapleListUrl(baseUrl: String, typeId: Int, page: Int): String {
    val category = SourceType.IMAPLE_TV.categoryMap.categoryFor(typeId)
        ?: throw ScraperException("Imaple 沒有分類 $typeId")
    val path = IMAPLE_CATEGORY_PATHS[category] ?: throw ScraperException("Imaple 不提供 $category")
    val separator = if ('?' in path) '&' else '?'
    return "$baseUrl$path${separator}pg=${page.coerceAtLeast(1)}"
}

internal fun imapleSearchUrl(baseUrl: String, keyword: String, page: Int): String =
    "$baseUrl/search?wd=${URLEncoder.encode(keyword.trim(), "UTF-8")}&pg=${page.coerceAtLeast(1)}"

internal data class ImaplePlay(val cuid: String, val episode: Int, val lineKey: String)

internal fun imaplePlayUrl(cuid: String, episode: Int, lineKey: String): String =
    "/play/$cuid/$episode?line=${URLEncoder.encode(lineKey, "UTF-8")}"

private val IMAPLE_PLAY_PATH = Regex("""^/play/([A-Za-z0-9]+)/(\d+)\?line=(.+)$""")

internal fun parseImaplePlayUrl(url: String): ImaplePlay? {
    val m = IMAPLE_PLAY_PATH.find(url) ?: return null
    return ImaplePlay(m.groupValues[1], m.groupValues[2].toInt(), URLDecoder.decode(m.groupValues[3], "UTF-8"))
}

private val ZERO_EPISODE_NAME = Regex("""第\s*0+\s*集""")

/**
 * 每條線路一個 EpisodeGroup。集數依位置編號：有些來源的集名是「第 0 集」這種錯的。
 * sourceId 用 1 起的小整數——跨來源增益以 -(ordinal*100 + lineId + 1) 推導負數 id，lineId 必須小於 100。
 */
internal fun imapleEpisodeGroups(cuid: String, lines: List<ImapleLine>): List<EpisodeGroup> {
    if (lines.isEmpty()) throw ScraperException("這部片沒有可播放的線路")
    return lines.mapIndexed { i, line ->
        EpisodeGroup(
            sourceName = line.name,
            sourceId = i + 1,
            episodes = line.episodeNames.mapIndexed { j, name ->
                val number = j + 1
                val title = if (name.isBlank() || ZERO_EPISODE_NAME.matches(name.trim())) {
                    "第${number.toString().padStart(2, '0')}集"
                } else {
                    name
                }
                Episode(number, title, imaplePlayUrl(cuid, number, line.lineKey))
            },
        )
    }
}
```

- [ ] **Step 6：跑測試，確認通過；再跑全部測試與建置**

Run: `./gradlew testDebugUnitTest --tests 'com.gimy.tv.data.scraper.ImapleTvSourceHelpersTest'`
Expected: PASS（10 個案例）
Run: `./gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL，0 failures（Hilt 會在這一步驗證 `EmbedSlugDao` 能注入）

- [ ] **Step 7：Commit**

```bash
git add app/src/main/java/com/gimy/tv/data/scraper/EmbedSlugResolution.kt app/src/main/java/com/gimy/tv/data/scraper/EmbeddedHlsSource.kt app/src/main/java/com/gimy/tv/domain/model/SiteCategoryMap.kt app/src/main/java/com/gimy/tv/data/scraper/ImapleTvSource.kt app/src/test/kotlin/com/gimy/tv/data/scraper/ImapleTvSourceHelpersTest.kt
git commit -m "Imaple 改接新平台" -m "cuid 以既有雜湊轉成 Long 並寫入 embed_slugs；找不到對照時丟 ScraperException，讓詳情頁載入失敗時由 SavedEntryRecovery 以片名找回改版前的舊收藏。"
```

---

### Task 5：三站合併為 Imaple

**Files:**
- Modify: `app/src/main/java/com/gimy/tv/domain/model/Models.kt`（`displayName` 之後新增 `isRetired`、`activeSourceTypes`）
- Create: `app/src/test/kotlin/com/gimy/tv/domain/model/ActiveSourceTypesTest.kt`
- Modify: `app/src/main/java/com/gimy/tv/data/preferences/SourcePreferencesRepository.kt`（第 40、44、61 行附近）
- Modify: `app/src/main/java/com/gimy/tv/ui/settings/SettingsScreen.kt`（第 145 行附近）
- Modify: `app/src/main/java/com/gimy/tv/data/endpoint/EndpointResolver.kt`（`DEFAULTS` 的 IMAPLE_TV、`refresh()` 第 206 行附近）
- Modify: `app/src/main/java/com/gimy/tv/di/RepositoryModule.kt`（刪除 EYNY_TV、KUBO123 綁定）
- Modify: `app/src/main/java/com/gimy/tv/data/repository/VodRepositoryImpl.kt`（建構子、`getSource`、`searchSourceOrder`）
- Modify: `app/src/main/java/com/gimy/tv/ui/home/HomeScreen.kt`（`MORE_SOURCES`，約第 563–572 行）
- Modify: `endpoints.json`（`imaple_tv`）

**Interfaces:**
- Consumes: Task 4 的 `ImapleTvSource`。
- Produces: `val SourceType.isRetired: Boolean`、`fun activeSourceTypes(): List<SourceType>`（package `com.gimy.tv.domain.model`）。

- [ ] **Step 1：寫會失敗的測試**

建立 `app/src/test/kotlin/com/gimy/tv/domain/model/ActiveSourceTypesTest.kt`：

```kotlin
package com.gimy.tv.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Eyny／Kubo 與 Imaple 是同一個資料庫，已併入 Imaple。它們若還留在啟用清單裡，聚合搜尋會把
 * 同一個 Imaple 查三次、每部片出現三份；設定頁也會冒出兩個沒有作用的開關。
 */
class ActiveSourceTypesTest {
    @Test fun `retired sources are excluded`() {
        assertThat(activeSourceTypes()).containsNoneOf(SourceType.EYNY_TV, SourceType.KUBO123)
    }

    /** 合併後的 Imaple 與其他來源都必須還在，只少退役的兩個。 */
    @Test fun `every other source stays active`() {
        assertThat(activeSourceTypes()).contains(SourceType.IMAPLE_TV)
        assertThat(activeSourceTypes()).hasSize(SourceType.values().size - 2)
    }
}
```

- [ ] **Step 2：跑測試，確認失敗**

Run: `./gradlew testDebugUnitTest --tests 'com.gimy.tv.domain.model.ActiveSourceTypesTest'`
Expected: FAIL，編譯錯誤 `Unresolved reference: activeSourceTypes`

- [ ] **Step 3：新增退役判斷**

在 `Models.kt` 的 `val SourceType.displayName` 整段之後加入：

```kotlin
/**
 * 已併入其他來源、不再使用的型別。enum 值保留：收藏與觀看紀錄存的是型別名稱字串，刪掉的話
 * 讀舊資料會在 valueOf 崩潰。2026-10-03 起 Eyny／Kubo 與 Imaple 是同一個資料庫，併入 IMAPLE_TV。
 */
val SourceType.isRetired: Boolean
    get() = this == SourceType.EYNY_TV || this == SourceType.KUBO123

/** 仍在使用的來源。列舉來源的地方（啟用清單、設定頁、端點探測）一律用這個，不要直接用 values()。 */
fun activeSourceTypes(): List<SourceType> = SourceType.values().filterNot { it.isRetired }
```

- [ ] **Step 4：跑測試，確認通過**

Run: `./gradlew testDebugUnitTest --tests 'com.gimy.tv.domain.model.ActiveSourceTypesTest'`
Expected: PASS（2 個案例）

- [ ] **Step 5：列舉來源的三處改用 `activeSourceTypes()`**

`SourcePreferencesRepository.kt`：把第 40、44、61 行附近的三個 `SourceType.values()` 都換成 `activeSourceTypes()`（若檔案沒有 `com.gimy.tv.domain.model.*` 的萬用匯入，加上 `import com.gimy.tv.domain.model.activeSourceTypes`）。改完後應為：

```kotlin
    override val enabledSources: StateFlow<Set<SourceType>> = dataStore.data
        .map { prefs ->
            activeSourceTypes().filter { type ->
                prefs[keyFor(type)] ?: true
            }.toSet()
        }
        .stateIn(scope, SharingStarted.Eagerly, activeSourceTypes().toSet())
```

```kotlin
    override suspend fun snapshot(): Set<SourceType> {
        val prefs = dataStore.data.first()
        return activeSourceTypes().filter { prefs[keyFor(it)] ?: true }.toSet()
    }
```

`SettingsScreen.kt` 第 145 行附近：`SourceType.values().forEach { type ->` 改成 `activeSourceTypes().forEach { type ->`（需要時補 import）。

`EndpointResolver.kt` 的 `refresh()`：

```kotlin
        val results = coroutineScope {
            // 退役的來源不探測：已不註冊，探測只是白白打已停用的網站。
            activeSourceTypes().map { type -> async { type to resolveOne(type, remoteConfig) } }.awaitAll()
        }
```

（`loadCache()` 裡的 `SourceType.values()` 不改：讀快取不發網路請求。`getBaseUrl()` 對退役型別會回落到 `DEFAULTS`，所以 `DEFAULTS` 的 EYNY_TV、KUBO123 項目保留。）

- [ ] **Step 6：Imaple 改用三網域候選**

`EndpointResolver.kt` 的 `DEFAULTS`，把 `SourceType.IMAPLE_TV to listOf(EndpointCandidate("https://imaple.tv", null)),` 換成：

```kotlin
            // 2026-10-03 起 imaple／eynytv／123kubo 是同一套新平台、同一個資料庫，三個網域互為備援。
            SourceType.IMAPLE_TV to listOf(
                EndpointCandidate("https://imaple.tv", null),
                EndpointCandidate("https://eynytv.com", null),
                EndpointCandidate("https://123kubo.net", null),
            ),
```

`endpoints.json` 的 `imaple_tv` 換成（`eyny_tv`、`kubo123` 保留不動：舊版 App 仍會讀它們）：

```json
    "imaple_tv": [
      "https://imaple.tv",
      "https://eynytv.com",
      "https://123kubo.net"
    ],
```

- [ ] **Step 7：取消註冊 Eyny／Kubo，舊型別導向 Imaple**

`RepositoryModule.kt`：刪除這兩段綁定（含上方的註解行，如果有）：

```kotlin
    @Binds @IntoMap @SourceTypeKey(SourceType.EYNY_TV)
    abstract fun bindEynyTvIntoMap(s: EynyTvSource): SiteSource
```

```kotlin
    @Binds @IntoMap @SourceTypeKey(SourceType.KUBO123)
    abstract fun bindKubo123IntoMap(s: Kubo123Source): SiteSource
```

`VodRepositoryImpl.kt`：
- 刪除檔案開頭的 `import com.gimy.tv.data.scraper.EynyTvSource`（第 6 行）與 `import com.gimy.tv.data.scraper.Kubo123Source`（第 13 行）——不刪的話 Task 6 刪掉類別後會編譯失敗。
- 建構子刪除 `private val eynyTvSource: EynyTvSource,` 與 `private val kubo123Source: Kubo123Source,` 兩行。
- `getSource()` 中的 `SourceType.EYNY_TV -> eynyTvSource`、`SourceType.IMAPLE_TV -> imapleTvSource`、`SourceType.KUBO123 -> kubo123Source` 三行，換成：

```kotlin
        // Eyny／Kubo 已併入 Imaple（同一個資料庫）。仍可能有舊收藏帶著這兩個型別：導向 Imaple 後，
        // 開啟失敗時 SavedEntryRecovery 會先在 Imaple 以片名找回，並改指為 IMAPLE_TV。
        SourceType.EYNY_TV, SourceType.KUBO123, SourceType.IMAPLE_TV -> imapleTvSource
```

- `searchSourceOrder` 改成：

```kotlin
    private val searchSourceOrder: List<SiteSource> by lazy {
        listOf(
            gimyTvSource, gimyMaxSource, imapleTvSource, gimyTwSource,
            momovodSource, movieffmSource,
        )
    }
```

- [ ] **Step 8：首頁三列改由 Imaple 提供**

`HomeScreen.kt` 的 `MORE_SOURCES` 換成（位置不變，原本 Kubo 與 Eyny 的兩列改成 Imaple 的韓劇與電影；列的 key 是 `more_<來源>_<分類>`，同來源不同分類不會撞 key）：

```kotlin
/** Configuration: which (source, category) combinations to surface as bonus rows.
 *  Imaple 同時提供三列：2026-10-03 起 eynytv／123kubo 已併入 Imaple，原本那兩列改由它供應，
 *  首頁排版不變。Each row only fetches when LazyColumn composes it. */
private val MORE_SOURCES: List<Pair<SourceType, StandardCategory>> = listOf(
    SourceType.IMAPLE_TV to StandardCategory.ANIME,
    SourceType.MOMOVOD to StandardCategory.CHINESE,
    SourceType.IMAPLE_TV to StandardCategory.KOREAN,
    SourceType.GIMY_TW to StandardCategory.CHINESE,
    SourceType.IMAPLE_TV to StandardCategory.MOVIE,
)
```

- [ ] **Step 9：跑全部測試與建置**

Run: `./gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL，0 failures
Run: `python3 -c "import json;d=json.load(open('endpoints.json'));print(d['endpoints']['imaple_tv'])"`
Expected: `['https://imaple.tv', 'https://eynytv.com', 'https://123kubo.net']`

- [ ] **Step 10：Commit**

```bash
git add app/src/main/java/com/gimy/tv/domain/model/Models.kt app/src/test/kotlin/com/gimy/tv/domain/model/ActiveSourceTypesTest.kt app/src/main/java/com/gimy/tv/data/preferences/SourcePreferencesRepository.kt app/src/main/java/com/gimy/tv/ui/settings/SettingsScreen.kt app/src/main/java/com/gimy/tv/data/endpoint/EndpointResolver.kt app/src/main/java/com/gimy/tv/di/RepositoryModule.kt app/src/main/java/com/gimy/tv/data/repository/VodRepositoryImpl.kt app/src/main/java/com/gimy/tv/ui/home/HomeScreen.kt endpoints.json
git commit -m "合併 eynytv、123kubo 至 Imaple" -m "三站已是同一個資料庫，保留三個來源只會讓搜尋每部片出現三次。Eyny／Kubo 的 enum 保留給舊資料，但不再註冊、列出或探測；舊收藏導向 Imaple 以片名找回。"
```

---

### Task 6：刪除三站的舊 MacCMS 程式碼

**Files:**
- Delete: `app/src/main/java/com/gimy/tv/data/scraper/EynyTvSource.kt`
- Delete: `app/src/main/java/com/gimy/tv/data/scraper/Kubo123Source.kt`
- Delete: `app/src/main/java/com/gimy/tv/data/scraper/parser/EynyTvParser.kt`
- Delete: `app/src/test/kotlin/com/gimy/tv/data/scraper/EynyTvParserTest.kt`
- Delete: `app/src/test/kotlin/com/gimy/tv/data/scraper/EynySearchParserTest.kt`

**Interfaces:** 無（純刪除；`MacCmsListBasedSource`、`MacCmsSearchUrl` 仍被 GimyTw、Momovod 使用，不動）。

- [ ] **Step 1：確認除了要刪的檔案之外沒有任何引用**

Run: `grep -rln "EynyTvSource\|EynyTvParser\|Kubo123Source" app/src`
Expected: 只列出上面五個要刪的檔案（Task 5 已移除 `RepositoryModule` 與 `VodRepositoryImpl` 的引用）。若列出其他檔案，停下來回報，不要刪。

- [ ] **Step 2：刪除**

```bash
git rm app/src/main/java/com/gimy/tv/data/scraper/EynyTvSource.kt app/src/main/java/com/gimy/tv/data/scraper/Kubo123Source.kt app/src/main/java/com/gimy/tv/data/scraper/parser/EynyTvParser.kt app/src/test/kotlin/com/gimy/tv/data/scraper/EynyTvParserTest.kt app/src/test/kotlin/com/gimy/tv/data/scraper/EynySearchParserTest.kt
```

- [ ] **Step 3：跑全部測試與建置**

Run: `./gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL，0 failures

- [ ] **Step 4：Commit**

```bash
git commit -m "移除 eynytv、123kubo 的舊爬蟲" -m "兩站已併入 Imaple 新平台，舊 MacCMS 解析與其測試不再有任何引用。"
```

---

### Task 7：模擬器端到端驗收

**Files:** 無程式變更。

- [ ] **Step 1：發版門檻**

Run: `./gradlew testDebugUnitTest assembleRelease`
Expected: BUILD SUCCESSFUL，0 failures；產出 `app/build/outputs/apk/release/app-release.apk`

- [ ] **Step 2：只對模擬器同簽章覆蓋安裝（保留資料）**

Run: `adb -s emulator-5554 install -r app/build/outputs/apk/release/app-release.apk`
Expected: `Success`。不得對 `RFCRB126PGM`（USB 實機）執行任何指令。

- [ ] **Step 3：重新探測網域**

啟動 App → 設定 →「清除快取」，等到顯示「已清除快取，站點網址也重新檢查過了」。
Expected: 來源管理只列出 Imaple、**不再**出現 Eyny 與 Kubo；Imaple 顯示筆數（約 28）且未標「失效」。

- [ ] **Step 4：首頁三列**

回首頁往下捲到「更多來源」。
Expected: Imaple 的動漫、韓劇、電影三列都有內容，卡片標籤為 IMP。

- [ ] **Step 5：搜尋只出現一份**

搜尋 `007`（adb 只能輸入英數）。
Expected: 來源篩選有 Imaple、**沒有** Eyny 與 Kubo；Imaple 的結果片名與關鍵字相關。

- [ ] **Step 6：詳情與播放**

從 Imaple 的一張卡片進詳情頁，播放第 1 集。
Expected: 看得到線路名稱與集數；播放後 `adb -s emulator-5554 shell dumpsys audio | grep -E "state:started"` 有 `com.gimy.tv` 的 AudioTrack 為 started。

- [ ] **Step 7：收藏在重開後仍打得開**

在 Step 6 的詳情頁按「收藏」→ `adb -s emulator-5554 shell am force-stop com.gimy.tv` → 重新開啟 App → 收藏頁點該片。
Expected: 詳情頁正常開啟（證明 cuid 對照有寫進 embed_slugs）。

- [ ] **Step 8：改版前的舊紀錄（模擬器上有才做）**

若收藏或紀錄頁有改版前的 Imaple／Eyny／Kubo 項目，點開它。
Expected: 自動找回並開啟，標籤變成 IMP；找不到時顯示既有的「已下架」訊息，而不是崩潰。

- [ ] **Step 9：回報**

把 Step 1–8 的結果（含截圖路徑）整理給使用者；`git push` 與遠端 `endpoints.json` 生效需另行徵得同意。
