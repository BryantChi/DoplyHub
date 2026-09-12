package com.gimy.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.gimy.tv.data.endpoint.EndpointResolver
import com.gimy.tv.data.local.dao.MovieffmSlugDao
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.gimy.tv.data.network.createDohDns
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class DoplyApp : Application(), ImageLoaderFactory {

    @Inject lateinit var endpointResolver: EndpointResolver

    @Inject lateinit var cfCookieStore: com.gimy.tv.data.network.CfCookieStore

    @Inject lateinit var cloudflareGateway: com.gimy.tv.data.network.CloudflareGateway

    @Inject lateinit var siteSources:
        javax.inject.Provider<Map<com.gimy.tv.domain.model.SourceType,
            @JvmSuppressWildcards com.gimy.tv.data.scraper.SiteSource>>
    @Inject lateinit var movieffmSlugDao: MovieffmSlugDao

    @Inject lateinit var embedSlugDao: com.gimy.tv.data.local.dao.EmbedSlugDao

    @Inject lateinit var jableTitleRepair: com.gimy.tv.data.repair.JableTitleRepair

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch { endpointResolver.warmUp() }

        // Restore a previously solved cf_clearance, then solve any endpoint still without
        // one. Aggregated search gives each source 5s while a challenge takes 6-20s, so
        // without this the first search always drops the gated sources. Order matters: the
        // cookie store must load first, or a clearance kept from a previous launch would be
        // re-solved for nothing.
        appScope.launch {
            runCatching {
                cfCookieStore.warmUp()
                cloudflareGateway.warmUp(
                    siteSources.get().values.mapNotNull { it.cloudflareWarmUpUrl }
                )
                // After the warm-up: jable is behind Cloudflare, so a repair attempted
                // before the clearance exists would fail every row and achieve nothing.
                jableTitleRepair.repairOnce()
            }
        }

        // Prune MovieFFM slug cache rows untouched for >30 days. Single DELETE WHERE,
        // negligible disk cost. Skips WorkManager scheduling because the slug table only
        // grows while the app is active anyway — running this on each launch is enough
        // to keep the table from accumulating across months/years of casual use. Errors
        // ignored: prune is best-effort, never blocks startup.
        appScope.launch {
            runCatching {
                val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
                movieffmSlugDao.pruneOlderThan(cutoff)
                // Same treatment for the jable/xnxx slug map added in v3.1.0.
                embedSlugDao.pruneOlderThan(cutoff)
            }
        }
    }

    /**
     * Adult-zone image CDNs (jable's `mushroomtrack`, xnxx's `xnxx-cdn`, 5278's
     * `hboav` / `ccccdn`) reject Coil's default "okhttp/x.y.z" User-Agent and/or
     * require a same-origin Referer. Override the network stack to use a desktop
     * browser UA and inject a per-host Referer based on the request URL.
     *
     * Other domains pass through unchanged — gimy.tw / imaple / etc. don't have
     * these guards so the default behavior was already fine for non-adult sources.
     */
    override fun newImageLoader(): ImageLoader {
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val client = OkHttpClient.Builder()
            // 封面圖也要走 DoH：有些來源的圖片就掛在被 DNS 過濾的網域上，
            // 只讓 API 繞過的話，列表有資料但封面全是黑框。
            .dns(createDohDns(this))
            // 這個 client 原本一個 timeout 都沒設，吃 OkHttp 預設的 connect/read 各 10 秒，
            // 並行上限更是預設的 64。首頁一次要載幾十張封面，等於瞬間開六十幾條連線，
            // 在電視盒上會把頻寬與 CPU 佔滿，把同時在跑的目錄抓取與端點探測一起拖垮
            // （清快取後重開特別明顯，因為所有封面都要重抓）。
            // 12 是估過的：一屏大約六到九張卡，夠用而不至於灌爆慢速裝置。
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .dispatcher(Dispatcher().apply {
                maxRequests = 12
                maxRequestsPerHost = 4
            })
            .addInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host
                val referer = when {
                    host.contains("jable") || host.contains("mushroom") -> "https://jable.tv/"
                    host.contains("xnxx-cdn") || host.contains("xnxx") -> "https://www.xnxx.com/"
                    host.contains("hboav") || host.contains("ccccdn") -> "https://5278.cc/"
                    host.contains("5278") || host.contains("neweratt") -> "https://5278.cc/"
                    else -> null
                }
                val builder = req.newBuilder().header("User-Agent", ua)
                if (referer != null) builder.header("Referer", referer)
                val isAdultHost = host.contains("xnxx") || host.contains("jable") ||
                    host.contains("mushroom") || host.contains("hboav") ||
                    host.contains("ccccdn") || host.contains("5278") ||
                    host.contains("neweratt")
                val response = runCatching { chain.proceed(builder.build()) }
                if (isAdultHost) {
                    val info = response.fold(
                        onSuccess = { "HTTP ${it.code}" },
                        onFailure = { "ERROR ${it.javaClass.simpleName}: ${it.message}" },
                    )
                    android.util.Log.w("DoplyImg", "${req.url} → $info")
                }
                response.getOrThrow()
            }
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(client)
            // 關閉硬體點陣圖（Coil 在 API 26+ 預設 Bitmap.Config.HARDWARE）。
            // 硬體點陣圖是直接放在 GPU 的 AHardwareBuffer，繪製內容由驅動決定；
            // 電視盒（Amlogic/Mali）與模擬器的驅動會把回收後的 buffer 畫成殘影或雜訊，
            // 症狀是「第一次開正常，關掉再開所有封面變成亂碼色塊」——
            // 已在模擬器上重現並確認關掉此選項即完全恢復。
            // 代價是點陣圖改放 Java heap，記憶體多一些，但封面圖不大，可接受。
            .allowHardware(false)
            .crossfade(true)
            .build()
    }
}
