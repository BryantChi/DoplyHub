package com.gimy.tv.data.scraper

import com.gimy.tv.data.endpoint.EndpointResolver
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import javax.inject.Inject

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
 */
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

    override fun parseListCards(doc: Document): List<Vod> {
        val items = mutableListOf<Vod>()
        // Jable cards: <a href="https://jable.tv/videos/{slug}/" title="..."><img data-src="..."></a>
        for (card in doc.select("a[href*=/videos/]")) {
            val href = card.attr("href")
            val match = Regex("/videos/([^/?\"]+)/?").find(href) ?: continue
            val slug = match.groupValues[1]
            if (slug.isBlank() || slug == "videos") continue

            val title = card.attr("title").trim().ifBlank {
                card.parent()?.selectFirst(".title, h6")?.text()?.trim().orEmpty()
            }.ifBlank { card.text().trim().take(80) }
            if (title.isBlank()) continue

            val cover = card.selectFirst("img")?.let { img ->
                listOf(img.attr("data-src"), img.attr("src")).firstOrNull { it.isNotBlank() }
            }.orEmpty()

            val id = stableId(slug)
            items.add(Vod(id, sourceType, title, cover, "", 0, ""))
        }
        return items.distinctBy { it.id }
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
