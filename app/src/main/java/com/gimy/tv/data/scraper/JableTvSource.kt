package com.gimy.tv.data.scraper

import com.gimy.tv.data.endpoint.EndpointResolver
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.domain.util.parseEpisodeStatus
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Jable.tv — JAV aggregator.
 *
 * URL forms:
 *   List   /hot/, /latest-updates/, /tags/{tag}/, /categories/{cat}/, /models/{model}/
 *   Detail /videos/{slug}/      (slug like "fns-203")
 *   Pagination uses ?from={page}
 *
 * Detail page m3u8: var hlsUrl = 'https://...mushroomtrack.com/hls/{TOKEN}/{TS}/.../...m3u8'
 * Token is IP-bound + Unix timestamp (~1 day TTL); ExoPlayer on the same NAT IP can play.
 *
 * `@Singleton` is mandatory — the slug↔stableId cache is instance state. Without it,
 * AdultPlusViewModel and VodRepositoryImpl get different instances; cache populated by
 * the list page is invisible when DetailScreen later tries to resolve the slug.
 */
@Singleton
class JableTvSource @Inject constructor(
    client: OkHttpClient,
    endpointResolver: EndpointResolver,
) : EmbeddedHlsSource(client, endpointResolver) {

    override val sourceType = SourceType.JABLE_TV

    override val hlsRegex = Regex("""var hlsUrl ?= ?['"]([^'"]+\.m3u8[^'"]*)['"]""")

    override fun buildListUrlForPath(path: String, page: Int): String {
        // Caller passes opaque path keys: "hot", "latest-updates", "tags/japanese", "categories/jav"
        val cleanPath = path.trim('/')
        val baseUrlPath = if (cleanPath.isEmpty()) "/hot/" else "/$cleanPath/"
        return if (page <= 1) "$baseUrl$baseUrlPath" else "$baseUrl$baseUrlPath?from=$page"
    }

    /**
     * Jable's Bootstrap pagination doesn't render a `下一頁/Next/rel=next` anchor —
     * it lists numeric page-links plus a "最後 »" jump. Each `.page-link` carries
     * `data-parameters="sort_by:…;from:N"`; the largest N is the last page.
     */
    override fun parseMaxPage(doc: Document): Int? {
        val fromRegex = Regex("from:(\\d+)")
        val max = doc.select(".pagination a.page-link[data-parameters]")
            .mapNotNull { fromRegex.find(it.attr("data-parameters"))?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull()
        return max?.takeIf { it > 0 }
    }

    override fun parseListCards(doc: Document): List<Vod> {
        // Jable cards split title and cover across TWO anchors per video:
        //   1. thumb anchor — wraps <img> + <span class="label">2:17:00</span>
        //   2. title anchor — <h6 class="title"><a>...</a></h6>
        //
        // Multi-pass merge by slug is more robust than a single selector chain because
        // jable's HTML varies by UA / cookie / locale (some users see the layout
        // collapsed into a different wrapper). We collect each piece separately and
        // emit one Vod per slug only if BOTH title and a real (non-placeholder) title text
        // are present.
        val titleBySlug = HashMap<String, String>()
        val coverBySlug = HashMap<String, String>()
        val placeholderTitles = setOf("視頻", "视频", "Video", "影片", "加載中", "Loading")

        // Pass 1: titles — prefer h6.title.a, fall back to any link with [title] attr,
        // else the title- / link-attribute on the link.
        for (a in doc.select("h6.title a[href*=/videos/], h5.title a[href*=/videos/], .title a[href*=/videos/]")) {
            val slug = slugFrom(a.attr("href")) ?: continue
            val text = a.text().trim()
            if (text.isNotBlank() && text !in placeholderTitles) titleBySlug.putIfAbsent(slug, text)
        }
        // Secondary title source: any anchor with a non-placeholder title attribute
        for (a in doc.select("a[href*=/videos/][title]")) {
            val slug = slugFrom(a.attr("href")) ?: continue
            val attrTitle = a.attr("title").trim()
            if (attrTitle.isNotBlank() && attrTitle !in placeholderTitles) titleBySlug.putIfAbsent(slug, attrTitle)
        }

        // Pass 2: covers — any <a href=videos/...> that contains an <img>.
        for (a in doc.select("a[href*=/videos/]")) {
            val slug = slugFrom(a.attr("href")) ?: continue
            if (coverBySlug.containsKey(slug)) continue
            val img = a.selectFirst("img") ?: continue
            val url = listOf(img.attr("data-src"), img.attr("src"))
                .firstOrNull { it.isNotBlank() && !it.contains("blank") }
                ?: continue
            coverBySlug[slug] = url
        }

        return titleBySlug.entries.map { (slug, title) ->
            Vod(stableId(slug), sourceType, title, coverBySlug[slug].orEmpty(), "", 0, "",
                siteStatus = parseEpisodeStatus(""))
        }
    }

    private fun slugFrom(href: String): String? {
        val m = Regex("/videos/([^/?\"]+)/?").find(href) ?: return null
        val slug = m.groupValues[1]
        return slug.takeIf { it.isNotBlank() && it != "videos" }
    }

    override fun detailUrlFor(vodId: Long): String {
        val slug = slugForId(vodId)
            ?: throw ScraperException("Jable slug not in cache for vodId=$vodId; visit a list page first")
        return "$baseUrl/videos/$slug/"
    }

    override fun parseDetailMeta(doc: Document, vodId: Long): Triple<String, String, Int> {
        val title = doc.selectFirst("h6.title, h1.title, h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Unknown"
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        return Triple(title, cover, 0)
    }
}
