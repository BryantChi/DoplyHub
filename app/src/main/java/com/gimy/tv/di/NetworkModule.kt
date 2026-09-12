package com.gimy.tv.di

import android.content.Context
import com.gimy.tv.data.network.CfCookieStore
import com.gimy.tv.data.network.CloudflareInterceptor
import com.gimy.tv.data.network.createDohDns
import com.gimy.tv.data.network.SearchRateLimitInterceptor
import com.gimy.tv.data.network.WebViewUserAgentProvider
import com.gimy.tv.data.network.httpUserAgent
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
        cookieStore: CfCookieStore,
        cloudflareInterceptor: CloudflareInterceptor,
        searchRateLimitInterceptor: SearchRateLimitInterceptor,
        userAgentProvider: WebViewUserAgentProvider,
    ): OkHttpClient {
        val cacheDir = File(context.cacheDir, "http_cache")
        val cache = Cache(cacheDir, 20L * 1024 * 1024) // 20MB

        return OkHttpClient.Builder()
            .cache(cache)
            // 走 DoH 而非裝置的 DNS：使用者家中的 ISP DNS 對部分來源做了 RPZ 過濾，
            // 解析會被導到封鎖頁（自簽憑證），錯誤訊息與「CDN 換根憑證」難以分辨。
            // 詳見 createDohDns 的說明，含解析失敗時退回系統 DNS 的理由。
            .dns(createDohDns(context))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            // 整個 call 的絕對上限。這條是必要的：專案裡所有抓取都用阻塞的 execute()，
            // 而 coroutine 的 withTimeout 只在掛起點生效，中斷不了阻塞呼叫——上層宣告
            // 「搜尋 5 秒、取流 8 秒」，實際卻可能耗到 connect 8s + read 15s + 重導向數次。
            // callTimeout 是 OkHttp 自己的計時器，會真的把 call 取消掉。
            // 取 20 秒是「絕對不該超過」的上限，不是預期值：正常站台 2-5 秒就回，
            // 所以不會誤殺任何本來會成功的請求。
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            // Without a jar every request went out cookie-less, so a solved Cloudflare
            // challenge could never be reused. cf_clearance lives here.
            .cookieJar(cookieStore)
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = 10           // global max concurrent requests
                maxRequestsPerHost = 4     // per-host limit to avoid throttling
            })
            .connectionPool(okhttp3.ConnectionPool(8, 3, TimeUnit.MINUTES))
            // Added before the header interceptor so the retried request still passes
            // through it and carries the same User-Agent the challenge was solved with.
            .addInterceptor(cloudflareInterceptor)
            // After the Cloudflare one: a challenge must clear before a 200 body
            // can be inspected for the rate-limit interstitial.
            .addInterceptor(searchRateLimitInterceptor)
            .addInterceptor { chain ->
                // Only set defaults when caller hasn't supplied them. Without this guard, the
                // interceptor overrides per-call headers — e.g. GitHub API expects
                // application/vnd.github+json and rejects text/html with HTTP 415.
                val original = chain.request()
                val builder = original.newBuilder()
                if (original.header("User-Agent") == null) {
                    builder.header("User-Agent", httpUserAgent(userAgentProvider.userAgent))
                }
                if (original.header("Accept") == null) {
                    builder.header("Accept", "text/html,application/xhtml+xml")
                }
                if (original.header("Accept-Language") == null) {
                    builder.header("Accept-Language", "zh-TW,zh;q=0.9")
                }
                chain.proceed(builder.build())
            }
            .build()
    }

    /**
     * 播放串流專用的 client，刻意不重用主 client。
     *
     * 差別與理由：
     * - **沒有磁碟快取**：HLS 分段與影片位元組沒有重用價值，塞進那個 20MB 的共用快取
     *   只會把目錄與詳情頁的快取整個擠掉。
     * - **沒有 Cloudflare / 限流攔截器**：那兩個會讀回應內容來判斷是不是挑戰頁，
     *   對動輒數 MB 的影片串流做這件事既無意義又耗記憶體。
     * - **沒有 callTimeout**：主 client 那個 20 秒上限對串流是有害的——一個 call 本來
     *   就會持續整段播放。只留 connect 與 read 的上限。
     *
     * 保留的是 DNS：這正是換掉 DefaultHttpDataSource 的目的。ExoPlayer 原本走系統的
     * HttpURLConnection，完全不吃 App 的 DNS 設定，於是播放用的 CDN 一旦被 ISP 的 DNS
     * 過濾（實測「極速雲」的 v2.ppqrrs.com 被導向封鎖頁），App 端毫無辦法。
     */
    @Provides
    @Singleton
    @PlaybackHttp
    fun providePlaybackClient(@ApplicationContext context: Context): OkHttpClient =
        OkHttpClient.Builder()
            .dns(createDohDns(context))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

    /**
     * 餵給 ExoPlayer 的資料來源。
     *
     * User-Agent 用瀏覽器字串而非 OkHttp 預設的 "okhttp/4.x"：影片 CDN 常以 UA 擋非瀏覽器
     * 流量，而 App 其他請求本來就用這個字串、已知能通。
     */
    @Provides
    @Singleton
    @UnstableApi
    fun provideMediaDataSourceFactory(
        @PlaybackHttp client: OkHttpClient,
        userAgentProvider: WebViewUserAgentProvider,
    ): DataSource.Factory =
        OkHttpDataSource.Factory(client)
            .setUserAgent(httpUserAgent(userAgentProvider.userAgent))
}

/** 標記播放串流專用的 OkHttpClient，與一般 API 請求那個區分開。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlaybackHttp
