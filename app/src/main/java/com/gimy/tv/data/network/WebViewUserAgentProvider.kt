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
    /**
     * Resolved once and cached — the first call inflates WebView internals and is not cheap.
     *
     * 「不便宜」有具體數字。2026-09-15 實機（SM-A326B，release）實測：這一行會讓
     * chromium 整個載進來（約 42 ms），並常駐一個 sandboxed_process0 renderer
     * 行程（PSS 6.35 MB）。把它改成永遠回 null、清空快取後冷啟動做對照，
     * chromium 與 renderer 都是 0，而首頁（gimy 10 列／ffm 9 列）與搜尋
     * （158 筆、八個來源全回應）完全不受影響。
     *
     * 也就是說，平常的抓取根本用不到真實 UA——全專案只有 JableTvSource 宣告
     * cloudflareWarmUpUrl，而 CloudflareInterceptor 只在收到 403/503 才出手。
     * 這個成本目前是所有人都付、只有開了成人進階區的人才用得到。
     *
     * 留著不改是刻意的：Cloudflare 這條路徑改壞的症狀是「某個站突然連不上」，
     * 很難查，而 42 ms 與 6.35 MB 在目標裝置上影響有限。真要省的話方向是
     * 「按需借」——平常用 FALLBACK_HTTP_USER_AGENT，等 CloudflareInterceptor
     * 真的遇到挑戰、或進階區被打開時才解析這個值。
     */
    val userAgent: String? by lazy {
        runCatching { WebSettings.getDefaultUserAgent(context) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    val isWebViewAvailable: Boolean get() = userAgent != null
}
