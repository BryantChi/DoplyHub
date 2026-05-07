package com.gimy.tv.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
        val cacheDir = File(context.cacheDir, "http_cache")
        val cache = Cache(cacheDir, 20L * 1024 * 1024) // 20MB

        return OkHttpClient.Builder()
            .cache(cache)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = 10           // global max concurrent requests
                maxRequestsPerHost = 4     // per-host limit to avoid throttling
            })
            .connectionPool(okhttp3.ConnectionPool(8, 3, TimeUnit.MINUTES))
            .addInterceptor { chain ->
                // Only set defaults when caller hasn't supplied them. Without this guard, the
                // interceptor overrides per-call headers — e.g. GitHub API expects
                // application/vnd.github+json and rejects text/html with HTTP 415.
                val original = chain.request()
                val builder = original.newBuilder()
                if (original.header("User-Agent") == null) {
                    builder.header("User-Agent", "Mozilla/5.0 (Linux; Android 13; TV) AppleWebKit/537.36")
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
}
