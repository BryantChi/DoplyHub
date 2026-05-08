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
class Forum5278Source @Inject constructor(
    private val client: OkHttpClient,
    private val endpointResolver: EndpointResolver,
) : SiteSource {

    override val sourceType = SourceType.FORUM5278
    override val baseUrl: String get() = endpointResolver.getBaseUrl(sourceType)

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 10) Mobile Safari/537.36"

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
            // Cover: pick a non-icon image from the post body
            val cover = doc.select("div.t_msgfont img, div.postmessage img, img.zoom")
                .map { it.attr("file").ifBlank { it.attr("src") } }
                .firstOrNull { it.isNotBlank() && !it.contains("smil") && !it.contains("avatar") }
                .orEmpty()
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

    private fun parseListCards(doc: Document): List<Vod> {
        val items = mutableListOf<Vod>()
        // Discuz threads: <a href="thread-{id}-1-1.html" title="..." onclick="atarget(this)">
        for (link in doc.select("a[href^=thread-]")) {
            val href = link.attr("href")
            val match = Regex("""thread-(\d+)-1-1\.html""").find(href) ?: continue
            val threadId = match.groupValues[1].toLongOrNull() ?: continue
            val title = link.attr("title").trim().ifBlank { link.text().trim() }
            if (title.isBlank() || title.length < 5) continue
            // Skip pinned/version/announcement rows that pollute the list
            if (title.contains("版規") || title.contains("公告") || title.contains("瀏覽器支援") ||
                title.contains("更新") && title.length < 20) continue

            // Cover: thread row may have a preview image nearby (in the same <tr> for Discuz)
            val cover = link.closest("tr")?.selectFirst("img.threadimg, img[src*=thread]")
                ?.attr("src").orEmpty()
            items.add(Vod(threadId, sourceType, title, cover, "", 0, ""))
        }
        return items.distinctBy { it.id }
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
