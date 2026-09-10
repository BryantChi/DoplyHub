package com.gimy.tv.data.scraper

import org.jsoup.nodes.Element

/** Matches a CSS `url(...)` value, with or without surrounding quotes. */
private val CSS_URL = Regex("""url\(\s*['"]?([^'")]+)['"]?\s*\)""")

/**
 * Poster URL for a MacCms list card.
 *
 * Most cards lazy-load via data-original, a few use data-src — and a minority carry no
 * attribute at all, inlining the image as a CSS background instead (10 of 154 cards on
 * 123kubo's drama listing, which rendered as blank tiles until this third form was covered).
 */
internal fun macCmsCardCover(card: Element): String {
    card.attr("data-original").trim().takeIf { it.isNotBlank() }?.let { return it }
    card.attr("data-src").trim().takeIf { it.isNotBlank() }?.let { return it }
    return CSS_URL.find(card.attr("style"))?.groupValues?.get(1)?.trim().orEmpty()
}
