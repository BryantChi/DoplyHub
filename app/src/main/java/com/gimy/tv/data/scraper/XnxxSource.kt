package com.gimy.tv.data.scraper

import com.gimy.tv.data.endpoint.EndpointResolver
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import javax.inject.Inject
import javax.inject.Singleton

/**
 * XNXX.com — international tube site with surprisingly broad Asian content in best/.
 * `@Singleton` is mandatory: slug↔stableId cache is instance state shared between
 * AdultPlusViewModel (writer) and VodRepositoryImpl (reader). Different instances
 * would silently break detail resolution.
 *
 * URL forms:
 *   List   /best/this_week, /best/today, /best/this_month, /tags/{tag}, /search/{kw}
 *   Detail /video-{id}/{slug}       (id like "1h3vtvb4", slug is SEO text)
 *   Pagination: append /{page} (e.g. /best/this_week/2)
 *
 * Detail page m3u8: html5player.setVideoHLS('https://hls-cdn77.xnxx-cdn.com/{TOKEN}/.../hls.m3u8')
 * Token is IP-bound; same-NAT-IP playback works.
 *
 * Note: home page (/) returns ad-only HTML for mobile UA. /best/this_week works on both
 * desktop and mobile UA, so we route all rows through /best/N and /tags/N paths.
 */
@Singleton
class XnxxSource @Inject constructor(
    client: OkHttpClient,
    endpointResolver: EndpointResolver,
    embedSlugDao: com.gimy.tv.data.local.dao.EmbedSlugDao,
) : EmbeddedHlsSource(client, endpointResolver, webViewUserAgentProvider = null, embedSlugDao = embedSlugDao) {

    override val sourceType = SourceType.XNXX

    override val hlsRegex = Regex("""html5player\.setVideoHLS\(['"]([^'"]+\.m3u8[^'"]*)['"]\)""")

    override fun buildListUrlForPath(path: String, page: Int): String {
        // Caller passes opaque path keys: "best/this_week", "tags/asian", "search/keyword"
        val cleanPath = path.trim('/')
        val basePath = if (cleanPath.isEmpty()) "/best/this_week" else "/$cleanPath"
        return if (page <= 1) "$baseUrl$basePath" else "$baseUrl$basePath/$page"
    }

    override fun parseListCards(doc: Document): List<Vod> {
        val items = mutableListOf<Vod>()
        // XNXX list HTML structure (verified against live HTML):
        //   <div class="thumb-block video" data-video='{...sfwThumbUrl JSON...}'>
        //     <div class="thumb">
        //       <a class="thumb-link" aria-label="Video">       ← no title text
        //         <img src=blank.gif data-src=real-thumb-url>
        //       </a>
        //     </div>
        //     <div class="thumb-under">
        //       <a class="title" href="/video-{id}/{slug}" title="real title">…</a>
        //     </div>
        //   </div>
        //
        // Previous bug: we iterated both .thumb-block AND .thumb-under (which is a CHILD
        // of thumb-block). Walking thumb-block first picked up the thumb-link anchor with
        // aria-label="Video" → matched the placeholder set → `continue` → emitted nothing
        // for that card. Then thumb-under iterated, found the real title-link, but had no
        // <img> nor data-video attr → cover became "". Net effect: every card ended up
        // with an empty cover URL on the listing page even though the parser otherwise ran.
        //
        // Fix: pivot only on .thumb-block and reach INTO it for both pieces — the title
        // anchor (a.title under .thumb-under) and the img (under .thumb).
        for (block in doc.select("div.thumb-block")) {
            // /best/* pages use <a class="title" href="...">; /tags/* and search pages
            // use the bare <a href="/video-..." title="..."> form inside <p> instead.
            // Match the broader `a[href*=/video-][title]` first so both structures
            // resolve to the same Vod row, then fall back to legacy class selectors.
            val titleLink = block.selectFirst(
                "a[href*=/video-][title], a.title, p.title a, .video-title a"
            ) ?: continue
            val href = titleLink.attr("href")
            val match = Regex("/video-([a-zA-Z0-9]+)/([^/?\"#]+)").find(href) ?: continue
            val videoId = match.groupValues[1]
            val slug = match.groupValues[2]
            if (videoId.isBlank()) continue

            val key = "$videoId/$slug"

            val title = titleLink.attr("title").trim().ifBlank { titleLink.text().trim() }
            if (title.isBlank() ||
                title in setOf("Video", "視頻", "视频", "影片", "加載中", "Loading"))
                continue

            // Cover. Browser-equivalent strategy:
            //   1) <img data-src=…/xn_NN_t.jpg> — what JS swaps in for src=blank.gif
            //   2) <img data-sfwthumb> — explicit SFW variant, also valid
            //   3) data-video JSON sfwThumbUrl — last-resort fallback (XNXX always ships it)
            val cover = run {
                block.selectFirst("a.thumb-link img, .thumb img, img")?.let { img ->
                    val candidates = listOf(
                        img.attr("data-src"),
                        img.attr("data-sfwthumb"),
                        img.attr("data-original"),
                        img.attr("src"),
                    )
                    val pick = candidates.firstOrNull { it.isNotBlank() && !it.contains("blank.gif") }
                    if (!pick.isNullOrBlank()) return@run pick
                }
                val dataVideo = block.attr("data-video")
                if (dataVideo.isNotBlank()) {
                    val m = Regex("\"sfwThumbUrl\"\\s*:\\s*\"([^\"]+)\"").find(dataVideo)
                    if (m != null) return@run m.groupValues[1].replace("\\/", "/")
                }
                ""
            }

            val id = stableId(key)
            items.add(Vod(id, sourceType, title, cover, "", 0, ""))
        }
        val unique = items.distinctBy { it.id }
        // Diagnostic — tag XnxxScrape. Drop once cover regression is known-good in the wild.
        unique.take(3).forEachIndexed { i, vod ->
            android.util.Log.w("XnxxScrape", "[$i] id=${vod.id} title=${vod.title.take(40)} cover=${vod.coverUrl}")
        }
        return unique
    }

    /**
     * XNXX uses `<div class="pagination">` with numeric anchors that point to
     * `/best/{period}/N`, `/tags/{slug}/N`, `/search/{kw}/N` — last URL segment IS the
     * page number, no rel=next anchor exists. Take the largest trailing-/N from any
     * anchor in a pagination block. Returns null if no pagination found (single-page
     * row) so the base class can fall through; AdultPlusBrowseViewModel's `gotNew`
     * gate will naturally stop infinite-load if items duplicate.
     */
    override fun parseMaxPage(doc: Document): Int? {
        val anchors = doc.select(".pagination a[href], nav.pagination a[href]")
        if (anchors.isEmpty()) return null
        val tailNum = Regex("/(\\d+)/?$")
        return anchors
            .mapNotNull {
                val href = it.attr("href").substringBefore('?').trimEnd('/')
                tailNum.find(href)?.groupValues?.get(1)?.toIntOrNull()
            }
            .maxOrNull()
            ?.takeIf { it > 0 }
    }

    override fun detailUrlFor(vodId: Long): String {
        val key = slugForId(vodId)
            ?: throw ScraperException("XNXX key not in cache for vodId=$vodId; visit a list page first")
        return "$baseUrl/video-$key"
    }

    override fun parseDetailMeta(doc: Document, vodId: Long): Triple<String, String, Int> {
        val html = doc.html()
        val title = Regex("""html5player\.setVideoTitle\(['"]([^'"]+)['"]\)""").find(html)?.groupValues?.get(1)
            ?: doc.selectFirst("h1, meta[property=og:title]")?.let {
                it.text().ifBlank { it.attr("content") }
            }?.trim()
            ?: "Unknown"
        val cover = Regex("""html5player\.setThumbUrl\(['"]([^'"]+)['"]\)""").find(html)?.groupValues?.get(1)
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        return Triple(unescapeHtml(title), cover, 0)
    }

    private fun unescapeHtml(s: String): String = s
        .replace("&amp;", "&")
        .replace("&#039;", "'")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}
