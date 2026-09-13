package com.gimy.tv.data.scraper

import com.gimy.tv.data.network.withBudget
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class ScraperException(message: String) : Exception(message)

/**
 * 所有 scraper 共用的抓頁面實作。
 *
 * 六個 source 原本各寫一份幾乎逐行相同的 fetchHtml，差別只在要不要帶 User-Agent 與
 * Referer。收成一份之後，逾時預算這種跨站共通的行為才有單一落點可以改，
 * 也不會再出現「某個 source 的錯誤訊息跟別人不一樣」這種無意義的分歧。
 *
 * [budgetMs] 為 null 時照用 client 的設定；有值時走 OkHttp 自己的計時器，
 * 詳見 [withBudget]。
 */
internal fun OkHttpClient.fetchHtml(
    url: String,
    userAgent: String? = null,
    referer: String? = null,
    budgetMs: Long? = null,
): String {
    val req = Request.Builder().url(url).apply {
        userAgent?.let { header("User-Agent", it) }
        referer?.let { header("Referer", it) }
    }.build()
    return newCall(req).withBudget(budgetMs).execute().use { resp ->
        if (!resp.isSuccessful) throw ScraperException("HTTP ${resp.code}: $url")
        resp.body?.string() ?: throw ScraperException("Empty body: $url")
    }
}

/** [fetchHtml] 再交給 Jsoup。baseUri 帶 url，相對連結才解得開。 */
internal fun OkHttpClient.fetchDocument(
    url: String,
    userAgent: String? = null,
    referer: String? = null,
    budgetMs: Long? = null,
): Document = Jsoup.parse(fetchHtml(url, userAgent, referer, budgetMs), url)
