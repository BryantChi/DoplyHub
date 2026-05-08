package com.gimy.tv.data.scraper

import com.gimy.tv.data.endpoint.EndpointResolver
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
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

    override fun parseListCards(doc: Document): List<Vod> {
        val items = mutableListOf<Vod>()
        // Jable card actually has TWO <a href="/videos/{slug}/"> elements per video:
        //   1. thumb anchor — wraps <img> + <span class="label">2:17:00</span> (duration)
        //   2. title anchor — inside <h6 class="title">, contains the actual title text
        // The old single-pass + distinctBy{id} pattern hit the thumb anchor first and
        // saved its text() = "2:17:00" as the title. Pivot to h6.title.a as source of
        // truth for title, then walk up to the common parent to grab the cover image.
        for (titleLink in doc.select("h6.title a[href*=/videos/]")) {
            val href = titleLink.attr("href")
            val match = Regex("/videos/([^/?\"]+)/?").find(href) ?: continue
            val slug = match.groupValues[1]
            if (slug.isBlank() || slug == "videos") continue

            val title = titleLink.text().trim()
            if (title.isBlank()) continue

            // Cover lives in a sibling thumb anchor's <img>. Walk up two levels to a
            // common card container (varies by template — .grid-item / .video-img-box
            // / generic div). Fallback to titleLink.parent if structure differs.
            val container = titleLink.closest("div.grid-item, div.video-img-box, .col, [class*=video]")
                ?: titleLink.parent()?.parent()
            val cover = container?.selectFirst("img")?.let { img ->
                listOf(img.attr("data-src"), img.attr("src"))
                    .firstOrNull { it.isNotBlank() && !it.contains("blank") }
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
