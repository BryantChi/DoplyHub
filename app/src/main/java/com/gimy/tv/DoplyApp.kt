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
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class DoplyApp : Application(), ImageLoaderFactory {

    @Inject lateinit var endpointResolver: EndpointResolver
    @Inject lateinit var movieffmSlugDao: MovieffmSlugDao

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch { endpointResolver.warmUp() }

        // Prune MovieFFM slug cache rows untouched for >30 days. Single DELETE WHERE,
        // negligible disk cost. Skips WorkManager scheduling because the slug table only
        // grows while the app is active anyway — running this on each launch is enough
        // to keep the table from accumulating across months/years of casual use. Errors
        // ignored: prune is best-effort, never blocks startup.
        appScope.launch {
            runCatching {
                val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
                movieffmSlugDao.pruneOlderThan(cutoff)
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
            .crossfade(true)
            .build()
    }
}
