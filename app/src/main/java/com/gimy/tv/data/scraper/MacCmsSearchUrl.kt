package com.gimy.tv.data.scraper

import java.net.URLEncoder

/**
 * Which search endpoint a MacCms site exposes.
 *
 * [VODSEARCH] is the stock template. [SEARCH_HTML] is used by the sites that also rename
 * their list/detail paths (momovod, 123kubo run /type and /vod instead of /vodtype and
 * /voddetail) — the search endpoint is renamed the same way, and assuming otherwise made
 * their search answer 404 while the source silently contributed nothing.
 */
enum class MacCmsSearchStyle { VODSEARCH, SEARCH_HTML }

/** Builds the search URL for a MacCms mirror. */
internal fun macCmsSearchUrl(baseUrl: String, keyword: String, style: MacCmsSearchStyle): String {
    val enc = URLEncoder.encode(keyword, "UTF-8")
    return when (style) {
        MacCmsSearchStyle.VODSEARCH -> "$baseUrl/vodsearch/-------------.html?wd=$enc"
        MacCmsSearchStyle.SEARCH_HTML -> "$baseUrl/search.html?wd=$enc"
    }
}
