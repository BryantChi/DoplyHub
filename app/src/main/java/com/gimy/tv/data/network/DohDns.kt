package com.gimy.tv.data.network

import android.content.Context
import okhttp3.Cache
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.File
import java.net.InetAddress

/**
 * 走 DNS over HTTPS 解析網域，不用裝置設定的那台 DNS。
 *
 * 為什麼需要：實測（2026-09-12）使用者家中的路由器把 DNS 轉給 ISP，而那台 DNS 對
 * `www.movieffm.net` 做了 RPZ 過濾——網域被解析到 182.173.0.181（TWNIC 的封鎖頁），
 * 該伺服器送自簽憑證，App 端看到的是 `SSLHandshakeException: Trust anchor for
 * certification path not found`，跟「CDN 換了新根憑證」的錯誤訊息一模一樣，極難分辨。
 * 走 DoH 直接向 1.1.1.1 問答案，就繞開了裝置那條被過濾的解析路徑。
 *
 * **限制**：只對走這個 OkHttpClient 的請求有效。ExoPlayer 的 DefaultHttpDataSource 用的是
 * 系統的 HttpURLConnection，不吃這裡的設定——播放用的 CDN 若哪天也被 DNS 擋，得另外改用
 * media3 的 OkHttpDataSource 才涵蓋得到。目前播放的 CDN 未被封鎖，暫不動它。
 */
private val dohLock = Any()

@Volatile
private var cachedDoh: Dns? = null

/**
 * 整個 process 共用同一個實例。
 *
 * 必須共用的理由不只是省資源：兩個 [Cache] 指向同一個目錄會互相破壞（OkHttp 的
 * DiskLruCache 對目錄獨佔），而 API 與封面圖是兩個不同的 OkHttpClient，各建一次就會撞上。
 */
internal fun createDohDns(context: Context): Dns =
    cachedDoh ?: synchronized(dohLock) {
        cachedDoh ?: buildDohDns(context).also { cachedDoh = it }
    }

private fun buildDohDns(context: Context): Dns {
    // 獨立的 client：不能重用主 client，否則主 client 要先有 DNS 才能建起來（循環依賴）。
    // 給它自己的磁碟快取，DoH 回應才能被快取住，不必每個新網域都多繞一次 HTTPS。
    val bootstrapClient = OkHttpClient.Builder()
        .cache(Cache(File(context.cacheDir, "doh_cache"), 5L * 1024 * 1024))
        .build()

    val doh = DnsOverHttps.Builder()
        .client(bootstrapClient)
        .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
        // 寫死 IP 是必要的：要解析 cloudflare-dns.com 這個名字本身就得先有 DNS，
        // 而那正是我們要繞開的東西。給字面 IP 不會觸發任何查詢。
        .bootstrapDnsHosts(
            InetAddress.getByName("1.1.1.1"),
            InetAddress.getByName("1.0.0.1"),
        )
        .build()

    return FallbackDns(doh)
}

/**
 * 先問 [primary]，問不到或出錯才退回 [fallback]。
 *
 * 一定要有這層：DoH 也可能連不上（機場／公司網路擋 1.1.1.1、離線、Cloudflare 故障），
 * 那時若讓解析直接失敗，等於整個 App 連不上任何站——比原本只少一個來源糟得多。
 *
 * 注意退回系統 DNS 並不會救回被 RPZ 擋掉的網域（系統 DNS 會「成功」回一個錯的 IP），
 * 這層的用途是保命，不是繞過封鎖。
 */
internal class FallbackDns(
    private val primary: Dns,
    private val fallback: Dns = Dns.SYSTEM,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val viaPrimary = try {
            primary.lookup(hostname)
        } catch (e: Exception) {
            android.util.Log.w("DohDns", "DoH 解析 $hostname 失敗，改用系統 DNS", e)
            emptyList()
        }
        if (viaPrimary.isNotEmpty()) return viaPrimary
        return fallback.lookup(hostname)
    }
}
