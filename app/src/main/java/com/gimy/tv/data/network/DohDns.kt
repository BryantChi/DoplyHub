package com.gimy.tv.data.network

import android.content.Context
import okhttp3.Cache
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * 需要繞過裝置 DNS 的網域。比對時含子網域，所以 `movieffm.net` 也涵蓋 `www.movieffm.net`。
 *
 * 為什麼是白名單而不是全部走 DoH：v3.1.9 曾讓**所有**請求都走 DoH，結果是把一個只影響
 * 單一來源的問題，換成了全體請求都多背一次 HTTPS 往返的風險——只要連 1.1.1.1 這一跳不順，
 * EndpointResolver 那 3 秒的探測就會整批逾時，表現成「所有來源都失效、整個 App 變慢」。
 * 為了一個來源去動全部請求的解析路徑，代價和收益完全不成比例。
 *
 * 新增網域前先確認它真的被 DNS 過濾（見下方判斷方式），不要因為「連不上」就加進來——
 * 連不上的原因多半不是 DNS。
 */
private val DOH_HOSTS = setOf(
    // 2026-09-12 實測：ISP 的 DNS 對它做 RPZ 過濾，解析到 182.173.0.181（TWNIC 封鎖頁），
    // 該伺服器送自簽憑證，App 端收到的是 SSLHandshakeException: Trust anchor ...not found，
    // 與「CDN 換了新根憑證」的錯誤訊息一字不差。
    // 判斷方式：adb shell ping -c 1 <host> 看實機解析到的 IP，與 dig <host> 對照。
    "movieffm.net",
)

private val dohLock = Any()

@Volatile
private var cachedDns: Dns? = null

/**
 * 整個 process 共用同一個實例。
 *
 * 必須共用的理由不只是省資源：兩個 [Cache] 指向同一個目錄會互相破壞（OkHttp 的
 * DiskLruCache 對目錄獨佔），而 API 與封面圖是兩個不同的 OkHttpClient，各建一次就會撞上。
 */
internal fun createDohDns(context: Context): Dns =
    cachedDns ?: synchronized(dohLock) {
        cachedDns ?: buildSelectiveDns(context).also { cachedDns = it }
    }

private fun buildSelectiveDns(context: Context): Dns {
    // 獨立的 client：不能重用主 client，否則主 client 要先有 DNS 才建得起來（循環依賴）。
    // 自己的磁碟快取讓 DoH 回應留得住，同一個網域不必每次都再繞一次 HTTPS。
    //
    // timeout 一定要設，而且要短：預設是 connect/read 各 10 秒，一旦 1.1.1.1 不通
    // （機場／公司網路擋 443、ISP 節流），每次解析都得等滿才退回系統 DNS，
    // 遠超過上層 EndpointResolver 那 3 秒的探測預算。callTimeout 是整體上限，
    // 確保連線加讀取加重試的總和不會超過它。
    val bootstrapClient = OkHttpClient.Builder()
        .cache(Cache(File(context.cacheDir, "doh_cache"), 5L * 1024 * 1024))
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .callTimeout(2000, TimeUnit.MILLISECONDS)
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

    return SelectiveDohDns(doh)
}

/**
 * 只有 [DOH_HOSTS] 裡的網域走 DoH，其餘一律交給系統 DNS。
 *
 * 這樣最壞情況也被關在一個來源裡：DoH 整個掛掉，受影響的只有清單上那些網域，
 * 其他請求的解析路徑與沒有這個類別時完全相同。
 */
internal class SelectiveDohDns(
    private val doh: Dns,
    private val system: Dns = Dns.SYSTEM,
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        if (!needsDoh(hostname)) return system.lookup(hostname)

        val viaDoh = try {
            doh.lookup(hostname)
        } catch (e: Exception) {
            android.util.Log.w("DohDns", "DoH 解析 $hostname 失敗，改用系統 DNS", e)
            emptyList()
        }
        // 退回系統 DNS 救不回被 RPZ 擋掉的網域（系統 DNS 會「成功」回一個錯的 IP），
        // 這一步的用途是保命：DoH 不可用時至少不要讓解析直接失敗。
        return viaDoh.ifEmpty { system.lookup(hostname) }
    }

    private fun needsDoh(hostname: String): Boolean {
        val host = hostname.lowercase()
        return DOH_HOSTS.any { host == it || host.endsWith(".$it") }
    }
}
