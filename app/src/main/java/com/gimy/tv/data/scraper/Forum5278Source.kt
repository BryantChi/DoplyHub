package com.gimy.tv.data.scraper

import com.gimy.tv.data.endpoint.EndpointResolver
import com.gimy.tv.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 5278.cc — Discuz BBS forum aggregator.
 *
 * URL forms:
 *   List   /forum-{forumId}-{page}.html  (forumId 23 = 成人線上, 42 = 線上性感影片)
 *   Thread /thread-{threadId}-1-1.html
 *
 * m3u8 extraction is two-layer:
 *   Layer 1: thread HTML contains <iframe class="cc5278_player" src="https://player.hboav.com/v4/public/Player.php?key={KEY}">
 *   Layer 2: player.hboav.com page directly embeds the m3u8 URL (path-based, no token)
 *
 * Unlike jable/xnxx, here the vodId IS the thread number (always digits) — no slug cache needed.
 */
@Singleton
class Forum5278Source @Inject constructor(
    private val client: OkHttpClient,
    private val endpointResolver: EndpointResolver,
) : SiteSource {

    override val sourceType = SourceType.FORUM5278
    override val baseUrl: String get() = endpointResolver.getBaseUrl(sourceType)

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 10) Mobile Safari/537.36"

    /**
     * Thread cover cache (tid → coverUrl). Populated from listing parses.
     *
     * Why: thread pages do NOT carry og:image and rarely have the cover image
     * in their post body — the listing's `<img>` URL (Discuz auto-generated
     * thumbnail under `neweratt.5278.cc/attachment/forum/threadcover/{aa}/{bb}/{tid}.jpg`)
     * is the only source. The {aa}/{bb} segments are unpredictable from tid
     * alone, so we can't reconstruct the URL — must remember what we saw on
     * the listing. Cold-start (e.g. opening from history) misses.
     *
     * Bounded LRU (1000) — without the cap this map grew forever as users browse
     * more threads. Eviction means a covered card might lose its image until the
     * user revisits the listing, which is acceptable.
     */
    private val coverCacheCapacity = 1000
    private val coverCache: MutableMap<Long, String> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<Long, String>(coverCacheCapacity, 0.75f, /* accessOrder = */ true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?): Boolean =
                    size > coverCacheCapacity
            }
        )

    /** Forums we surface. typeId = Discuz forum number. */
    override suspend fun fetchCategories(): List<Category> = listOf(
        Category(23, "成人線上", sourceType),
        Category(42, "線上性感影片", sourceType),
    )

    override suspend fun fetchVodList(typeId: Int, page: Int): PaginatedResult<Vod> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/forum-$typeId-$page.html"
            val doc = fetchDocument(url)
            val items = parseListCards(doc)
            val hasNext = doc.select("a:contains(下一頁), a.nxt, a[href*=forum-${typeId}-${page + 1}]").isNotEmpty()
            PaginatedResult(items, page, if (hasNext) page + 1 else page, hasNext || items.isNotEmpty())
        }

    override suspend fun fetchVodDetail(vodId: Long): VodDetail =
        withContext(Dispatchers.IO) {
            val doc = fetchDocument("$baseUrl/thread-$vodId-1-1.html")
            val rawTitle = doc.selectFirst("title")?.text()?.trim().orEmpty()
            // Strip site suffix from page title: "標題 - 5278 / 5278論壇 - " → "標題"
            val title = rawTitle.split(" - ").firstOrNull()?.trim()?.ifBlank { "Unknown" } ?: "Unknown"
            // Cover resolution priority:
            //   1) coverCache[tid] — populated when the listing was parsed. This is the
            //      threadcover URL Discuz generates and is the only reliable source.
            //   2) post-body <img> — usually empty (most threads embed video iframes only).
            //      The post body lives under `<td class="t_f" id="postmessage_NNN">`
            //      in current Discuz; older `t_msgfont/postmessage` are kept as fallback.
            //      Static Discuz icons (under `static/image/`) and avatar/smileys are
            //      filtered out.
            val cachedCover = coverCache[vodId]
            val cover = if (!cachedCover.isNullOrBlank()) cachedCover else {
                val rawCover = doc.select("td.t_f img, div.t_msgfont img, div.postmessage img, img.zoom")
                    .map { it.attr("file").ifBlank { it.attr("src") } }
                    .firstOrNull {
                        it.isNotBlank() &&
                            !it.contains("smil") &&
                            !it.contains("avatar") &&
                            !it.contains("static/image")
                    }
                    .orEmpty()
                when {
                    rawCover.isBlank() -> ""
                    rawCover.startsWith("//") -> "https:$rawCover"
                    rawCover.startsWith("http") -> rawCover
                    else -> "$baseUrl/${rawCover.trimStart('/')}"
                }.also { if (it.isNotBlank()) coverCache[vodId] = it }
            }
            // Single virtual episode — fetchPlayerData does the 2-layer extraction
            val ep = Episode(1, title, "embed:$vodId")
            val group = EpisodeGroup("HLS", 1, listOf(ep))
            VodDetail(
                Vod(vodId, sourceType, title, cover, "", 0, ""),
                "", emptyList(), "", listOf(group),
            )
        }

    override suspend fun fetchPlayerData(episodeUrl: String): PlayerData =
        withContext(Dispatchers.IO) {
            val vodId = episodeUrl.removePrefix("embed:").toLongOrNull()
                ?: throw ScraperException("Invalid embed url: $episodeUrl")
            val threadUrl = "$baseUrl/thread-$vodId-1-1.html"

            // Layer 1: thread HTML → grab cc5278_player iframe src
            val threadHtml = fetchHtml(threadUrl, baseUrl)
            val iframeMatch = Regex("""<iframe[^>]*class="cc5278_player"[^>]*src="([^"]+)"""")
                .find(threadHtml)
                ?: throw ScraperException("cc5278_player iframe not found in thread $vodId")
            val playerUrl = iframeMatch.groupValues[1].let {
                if (it.startsWith("//")) "https:$it" else it
            }

            // Layer 2: player.hboav.com → grab m3u8 URL
            val playerHtml = fetchHtml(playerUrl, threadUrl)
            val m3u8Match = Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""").find(playerHtml)
                ?: throw ScraperException("m3u8 not found in player page")

            PlayerData(m3u8Match.groupValues[1], encrypt = 0, from = "5278")
        }

    override suspend fun search(keyword: String, page: Int): PaginatedResult<Vod> =
        // Discuz native search requires login + form POST; not supported in this scraper
        PaginatedResult(emptyList(), page, 0, false)

    // ─── Parsing ───

    /**
     * 5278 forum-23 / forum-42 ship a portal-style listing now (no longer table-based).
     * Each thread sits inside `<li style="width:232px">` with:
     *   <a class="z" href="thread-{id}-1-1.html"><img src=cover></a>
     *   <h3 class="xw0"><a href="thread-{id}-1-1.html" title="{realTitle}">{realTitle}</a></h3>
     *   <cite class="xg1 y">喜歡: N  回復: <a>R</a></cite>
     *
     * Filtering strategy (post-mortem on the previous bugs):
     *   1. Title keyword block-list — covers pinned site rules + obvious ad threads.
     *   2. Cover URL must be an absolute http(s) URL — sponsored / ad threads use a
     *      relative `data/attachment/...` path that the CDN won't serve from the App.
     *      Real video threads always come back with `https://neweratt.5278.cc/...`.
     *   3. Fallback length guard — titles under 5 chars are stub posts.
     */
    private fun parseListCards(doc: Document): List<Vod> {
        val items = mutableListOf<Vod>()
        val seen = HashSet<Long>()
        // Block-list keywords. Order doesn't matter — first hit drops the row.
        val titleBlockKeywords = listOf(
            "版規", "公告", "瀏覽器支援", "教學", "置頂", "Sticky",
            "機器人", "夢想成真", "徵求", "徵稿", "邀請", "宣傳", "招募",
            "官方", "公告", "AI 偶像",
        )

        for (li in doc.select("li[style*=width:232], li[style*=width: 232]")) {
            val titleAnchor = li.selectFirst("h3.xw0 a[href^=thread-], h3 a[href^=thread-]") ?: continue
            // Discuz URL pattern is `thread-{tid}-{postPage}-{forumPage}.html`. Forum
            // page 1 emits `-1-1`, page 2 emits `-1-2`, etc. The previous regex was
            // pinned to `-1-1` so every thread on page ≥2 was rejected — `loadMore`
            // looked broken because we discarded everything we fetched. Match any
            // postPage and any forumPage suffix.
            val match = Regex("""thread-(\d+)-\d+-\d+\.html""").find(titleAnchor.attr("href")) ?: continue
            val threadId = match.groupValues[1].toLongOrNull() ?: continue
            if (!seen.add(threadId)) continue

            val title = titleAnchor.attr("title").trim().ifBlank { titleAnchor.text().trim() }
            if (title.isBlank() || title.length < 5) continue
            if (title.contains("更新") && title.length < 20) continue
            if (titleBlockKeywords.any { title.contains(it, ignoreCase = true) }) continue

            // Cover: first <img src> inside the li. Must be absolute.
            val cover = li.selectFirst("img[src]")?.attr("src").orEmpty()
            if (!cover.startsWith("http://") && !cover.startsWith("https://")) continue

            // Remember the threadcover URL for the detail page — see coverCache docstring.
            coverCache[threadId] = cover
            items.add(Vod(threadId, sourceType, title, cover, "", 0, ""))
        }
        return items
    }

    // ─── HTTP ───

    private fun fetchHtml(url: String, referer: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", referer)
            .build()
        return client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ScraperException("HTTP ${r.code}: $url")
            r.body?.string() ?: throw ScraperException("Empty body: $url")
        }
    }

    private fun fetchDocument(url: String): Document =
        Jsoup.parse(fetchHtml(url, baseUrl), url)
}
