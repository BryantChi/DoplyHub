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

    /**
     * 2026-10-04 列表頁改成 site-card 版型（h3 沒有 class 也沒有 title 屬性），搜尋頁仍是 stub 版型，
     * 兩種同時存在。選擇器綁 class 名稱的話，站方一改名就整列解析不到。
     */
    @Test fun `site-card list markup parses like the stub markup`() {
        val html = """
            <div><span class="filter-label">共 <span class="font-semibold">3,143</span> 部</span></div>
            <a class="navigation-link site-card site-card--excerpt" href="/title/cmuiku92e010onr2nevyt7pq7"><div class="site-card-image"><div class="skeleton absolute inset-0" aria-hidden="true"></div><img src="/pi/360/pZG9.webp" alt="潛入你心" loading="eager" class="h-full w-full object-cover"/></div><div class="site-card-copy"><h3>潛入你心</h3><p class="site-card-meta">更新至 EP 03</p><p class="site-card-blurb">尹夏娜是備受亞洲矚目的頂級女星。</p></div><span class="site-card-score" aria-label="評分 6.0">6.0</span><span aria-hidden="true" class="navigation-hint"></span></a>
            <a class="breadcrumb" href="/title/cmuiku92e010onr2nevyt7pq7">潛入你心</a>
        """.trimIndent()
        val cards = ImapleParser.parseCards(Jsoup.parse(html, base), base)
        assertThat(cards).hasSize(1)
        assertThat(cards[0].cuid).isEqualTo("cmuiku92e010onr2nevyt7pq7")
        assertThat(cards[0].title).isEqualTo("潛入你心")
        assertThat(cards[0].coverUrl).isEqualTo("https://imaple.tv/pi/360/pZG9.webp")
        assertThat(cards[0].status).isEqualTo("更新至 EP 03")
    }

    /** 只有文字、沒有海報也沒有片名標題的 /title/ 連結（麵包屑、內文連結）不是卡片。 */
    @Test fun `plain text links to a title are not cards`() {
        val html = """<p>相關：<a href="/title/cmaaaaaaaaaaaaaaaaaaaaaaa">另一部片</a></p>"""
        assertThat(ImapleParser.parseCards(Jsoup.parse(html, base), base)).isEmpty()
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
