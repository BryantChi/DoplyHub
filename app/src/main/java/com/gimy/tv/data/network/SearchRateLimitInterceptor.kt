package com.gimy.tv.data.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Waits out the Gimy backends' search rate limit and retries once.
 *
 * Separate from [CloudflareInterceptor] because both the trigger and the remedy differ: this
 * one fires on a *successful* 200 whose body is an interstitial, and the fix is simply to
 * wait rather than to solve anything.
 *
 * Only one retry: if the second attempt is throttled too, the caller is better served by an
 * empty result than by a request that blocks for another few seconds.
 */
class SearchRateLimitInterceptor @Inject constructor() : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) return response

        val preview = runCatching { response.peekBody(PEEK_BYTES).string() }.getOrDefault("")
        if (!isSearchRateLimited(preview)) return response

        response.close()
        runBlocking { delay(RETRY_DELAY_MS) }
        return chain.proceed(chain.request().newBuilder().build())
    }

    private companion object {
        // The page asks for 3 seconds; a little headroom avoids landing on the boundary.
        const val RETRY_DELAY_MS = 3_500L
        const val PEEK_BYTES = 8L * 1024
    }
}
