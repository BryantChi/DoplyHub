package com.gimy.tv.data.repair

/** What the scrapers write when they cannot determine a title. */
private const val PARSER_FALLBACK_TITLE = "Unknown"

/**
 * Guards the one-off title repair, which writes into the user's own favourites and history.
 *
 * A wrong-but-readable title is still better than "Unknown" or a blank, so anything short of
 * a real, different title leaves the existing row alone.
 */
internal fun shouldReplaceTitle(old: String, fetched: String?): Boolean {
    val clean = fetched?.trim().orEmpty()
    if (clean.isBlank()) return false
    if (clean == PARSER_FALLBACK_TITLE) return false
    return clean != old.trim()
}
