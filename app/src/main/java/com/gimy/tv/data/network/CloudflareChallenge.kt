package com.gimy.tv.data.network

import com.gimy.tv.domain.model.SourceType

/**
 * Pure decision logic for the Cloudflare gateway. Kept free of Android types so the
 * parts that are easy to get wrong — mistaking a permission error for a challenge,
 * or changing the UA of a source that never needed it — stay unit-testable on the JVM.
 */

/** Markers observed on the real interstitials captured 2026-09-10 (gimytv.me / gitube.tv / jable.tv). */
private val CHALLENGE_MARKERS = listOf("_cf_chl_opt", "cf_chl_opt", "Just a moment")

/**
 * A challenge needs BOTH a blocking status and a marker in the body.
 *
 * Status alone is not enough: a site's own permission error is also a 403, and solving a
 * challenge would never fix it — we would just burn a WebView launch on every request.
 * A marker alone is not enough either: an ordinary 200 page that happens to mention the
 * string must not trigger a solve.
 */
internal fun isCloudflareChallenge(code: Int, body: String): Boolean {
    if (code != 403 && code != 503) return false
    return CHALLENGE_MARKERS.any { body.contains(it) }
}

/** UA used by EmbeddedHlsSource before Cloudflare handling existed; still correct for XNXX. */
internal const val DEFAULT_EMBED_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Safari/537.36"

/** Used when this device has no usable WebView to borrow a real UA from. */
internal const val FALLBACK_HTTP_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13; TV) AppleWebKit/537.36"

/**
 * cf_clearance is bound to the User-Agent that solved the challenge, so a source sitting
 * behind Cloudflare must send exactly what the WebView sent.
 *
 * Only JABLE_TV qualifies. XNXX shares [EmbeddedHlsSource] with it but is not behind
 * Cloudflare — switching its UA would gain nothing and put a working source at risk,
 * which is why this is a per-source decision rather than a change to the shared parent.
 */
internal fun embedUserAgent(sourceType: SourceType, webViewUserAgent: String?): String =
    when (sourceType) {
        SourceType.JABLE_TV -> webViewUserAgent ?: DEFAULT_EMBED_USER_AGENT
        else -> DEFAULT_EMBED_USER_AGENT
    }

/**
 * Default UA for the shared OkHttp client. The Gimy mirrors reach Cloudflare through this
 * path (their sources set no UA of their own), so it borrows the WebView's real UA too.
 */
internal fun httpUserAgent(webViewUserAgent: String?): String =
    webViewUserAgent ?: FALLBACK_HTTP_USER_AGENT


/** Interstitials the Gimy backends return when searches arrive too quickly (observed 2026-09-10). */
private val RATE_LIMIT_MARKERS = listOf("搜尋太頻繁", "搜索太频繁")

/**
 * The rate-limit page comes back as HTTP 200, so the status line claims success and only the
 * body gives it away. Aggregated search hits GimyTV and GimyMax — the same backend — at once,
 * which is precisely what triggers it.
 */
internal fun isSearchRateLimited(body: String): Boolean =
    RATE_LIMIT_MARKERS.any { body.contains(it) }
