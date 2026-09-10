package com.gimy.tv.data.network

import android.content.Context
import android.webkit.WebSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies this device's real WebView User-Agent.
 *
 * The direction matters: rather than dressing the WebView up as our hard-coded UA, we let
 * OkHttp borrow the WebView's genuine one. A browser claiming to be X while fingerprinting
 * as Y is exactly the signal Cloudflare looks for, and cf_clearance is bound to whichever
 * UA solved the challenge — so both sides must send the same string.
 *
 * Returns null when this device has no usable WebView (non-certified boxes exist);
 * callers fall back to the previous hard-coded values and behave as before.
 */
@Singleton
class WebViewUserAgentProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Resolved once and cached — the first call inflates WebView internals and is not cheap. */
    val userAgent: String? by lazy {
        runCatching { WebSettings.getDefaultUserAgent(context) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    val isWebViewAvailable: Boolean get() = userAgent != null
}
