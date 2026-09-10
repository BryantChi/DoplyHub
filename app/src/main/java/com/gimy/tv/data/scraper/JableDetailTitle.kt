package com.gimy.tv.data.scraper

import org.jsoup.nodes.Document

/**
 * Picks the title of the video actually being viewed on a Jable detail page.
 *
 * The page ends with a recommendation grid built from the list-page card template, so
 * `h6.title` matches ~24 *other* videos. The previous selector led with it and
 * `selectFirst` returned whichever card the DOM placed first — which is why watch history
 * recorded a completely unrelated title. There is also no `h1` on these pages at all, so
 * that part of the old chain never contributed anything.
 *
 * og:title is the site's own statement of the current video and was measured identical to
 * the info-box heading, with no site-name suffix. The h4 fallback is scoped to the info box
 * because the recommendation section has an h4 heading of its own.
 */
internal fun jableDetailTitle(doc: Document): String {
    doc.selectFirst("meta[property=og:title]")
        ?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
        ?.let { return it }

    doc.selectFirst(".info-header h4, .video-info h4, .header-left h4")
        ?.text()?.trim()?.takeIf { it.isNotBlank() }
        ?.let { return it }

    return "Unknown"
}
