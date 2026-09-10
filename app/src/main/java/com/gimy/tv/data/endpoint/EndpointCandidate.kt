package com.gimy.tv.data.endpoint

import com.gimy.tv.data.scraper.parser.GimyLayout
import com.gimy.tv.data.scraper.parser.GimyPaths

/**
 * One candidate endpoint from endpoints.json.
 *
 * [profile] names which template/path combination the mirror serves. The original schema was
 * a bare URL list, which assumed all candidates for a source parsed identically — true until
 * the Gimy mirrors turned out to serve the same catalogue under three different combinations.
 * A null profile keeps the source's own default, so old bare-URL configs still work.
 */
data class EndpointCandidate(val url: String, val profile: String?)

/** Builds a candidate, rejecting entries with no usable URL. */
internal fun endpointCandidate(url: String?, profile: String?): EndpointCandidate? {
    val cleanUrl = url?.trim().orEmpty()
    if (cleanUrl.isBlank()) return null
    return EndpointCandidate(cleanUrl, profile?.trim()?.takeIf { it.isNotBlank() })
}

/** Path tokens plus template for one Gimy mirror. */
data class GimyMirror(val paths: GimyPaths, val layout: GimyLayout)

/**
 * Resolves a profile name to its mirror settings.
 *
 * Unknown names fall back to the classic layout rather than failing: a future endpoints.json
 * may name a profile this build does not know yet, and degrading to "parses nothing" would
 * be worse than trying the most common template.
 */
internal fun gimyMirrorFor(profile: String?): GimyMirror = when (profile?.trim()?.lowercase()) {
    "poster" -> GimyMirror(GimyPaths(list = "/genre", detail = "/detail", episode = "/play", search = "/find"), GimyLayout.POSTER)
    "browse" -> GimyMirror(GimyPaths(list = "/browse", detail = "/title", episode = "/watch", search = "/search"), GimyLayout.CARD)
    else -> GimyMirror(GimyPaths(list = "/type", detail = "/vod", episode = "/ep", search = "/search"), GimyLayout.CARD)
}
