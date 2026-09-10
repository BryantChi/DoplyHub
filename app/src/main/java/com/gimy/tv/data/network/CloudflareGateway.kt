package com.gimy.tv.data.network

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Solves Cloudflare Managed Challenges with an offscreen WebView and hands the resulting
 * cf_clearance to OkHttp.
 *
 * Verified 2026-09-10: the interstitials on gimytv.me, gitube.tv and jable.tv clear
 * themselves in 6–8 seconds with no user interaction, so a WebView can do this unattended.
 * If Cloudflare ever escalates to an interactive captcha this returns false and callers
 * degrade to the pre-existing behaviour rather than hanging.
 *
 * cf_clearance is HttpOnly, so page JavaScript cannot read it — CookieManager can, because
 * it sits below the JS sandbox.
 */
@Singleton
class CloudflareGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cookieStore: CfCookieStore,
    private val userAgentProvider: WebViewUserAgentProvider,
) {
    /** One solve at a time per host: a cold home screen fires ten category requests at once,
     *  and without this each would spawn its own WebView for the same challenge. */
    private val hostLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun solve(url: HttpUrl): Boolean {
        if (!userAgentProvider.isWebViewAvailable) return false
        val lock = hostLocks.getOrPut(url.host) { Mutex() }
        return lock.withLock {
            // A parallel request may have solved it while we waited for the lock.
            if (cookieStore.rawFor(url.host)?.contains(CLEARANCE_COOKIE) == true) return@withLock true
            runCatching { solveInWebView(url) }.getOrDefault(false)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun solveInWebView(url: HttpUrl): Boolean = withContext(Dispatchers.Main) {
        val webView = WebView(context).apply {
            settings.javaScriptEnabled = true          // the challenge is a JS computation
            settings.domStorageEnabled = true
            userAgentProvider.userAgent?.let { settings.userAgentString = it }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // A main-frame failure means there is nothing to solve — fail fast instead of
        // burning the full timeout. Sub-resource errors are ignored: challenge pages pull
        // in assets that routinely fail without affecting the outcome.
        var mainFrameFailed = false
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) mainFrameFailed = true
            }
        }
        try {
            webView.loadUrl(url.toString())
            val cleared = withTimeoutOrNull(SOLVE_TIMEOUT_MS) {
                while (true) {
                    delay(POLL_INTERVAL_MS)
                    if (mainFrameFailed) return@withTimeoutOrNull null
                    val raw = CookieManager.getInstance().getCookie(url.toString())
                    if (raw != null && raw.contains(CLEARANCE_COOKIE)) return@withTimeoutOrNull raw
                }
                @Suppress("UNREACHABLE_CODE") null
            }
            if (cleared == null) return@withContext false
            CookieManager.getInstance().flush()
            cookieStore.putRaw(url.host, cleared)
            true
        } finally {
            webView.stopLoading()
            webView.destroy()
        }
    }

    private companion object {
        const val CLEARANCE_COOKIE = "cf_clearance"
        const val SOLVE_TIMEOUT_MS = 20_000L
        const val POLL_INTERVAL_MS = 400L
    }
}

/**
 * Retries a challenged request once, after the gateway has obtained a clearance.
 *
 * Only once: if the retry is challenged again the clearance is not working, and looping
 * would pin the device on WebView launches.
 */
class CloudflareInterceptor @Inject constructor(
    private val gateway: CloudflareGateway,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.code != 403 && response.code != 503) return response

        // peekBody leaves the original body intact for the caller if this turns out
        // not to be a challenge.
        val preview = runCatching { response.peekBody(PEEK_BYTES).string() }.getOrDefault("")
        if (!isCloudflareChallenge(response.code, preview)) return response

        val solved = runBlocking { gateway.solve(request.url) }
        if (!solved) return response

        response.close()
        return chain.proceed(request.newBuilder().build())
    }

    private companion object {
        const val PEEK_BYTES = 64L * 1024
    }
}
