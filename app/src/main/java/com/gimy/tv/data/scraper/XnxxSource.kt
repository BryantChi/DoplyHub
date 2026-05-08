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
) : EmbeddedHlsSource(client, endpointResolver) {

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
        // Cards: <a href="/video-{id}/{slug}" title="..."><img src="..."></a>
        for (card in doc.select("a[href*=/video-]")) {
            val href = card.attr("href")
            val match = Regex("/video-([a-zA-Z0-9]+)/([^/?\"#]+)").find(href) ?: continue
            val videoId = match.groupValues[1]
            val slug = match.groupValues[2]
            if (videoId.isBlank()) continue

            // Use "{videoId}/{slug}" as cache key so detailUrlFor can rebuild full URL
            val key = "$videoId/$slug"

            val title = card.attr("title").trim().ifBlank {
                card.text().trim().take(80)
            }
            if (title.isBlank()) continue

            val cover = card.selectFirst("img")?.let { img ->
                listOf(img.attr("data-src"), img.attr("data-original"), img.attr("src"))
                    .firstOrNull { it.isNotBlank() }
            }.orEmpty()

            val id = stableId(key)
            items.add(Vod(id, sourceType, title, cover, "", 0, ""))
        }
        return items.distinctBy { it.id }
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
