package com.gimy.tv.data.cache

import android.content.Context
import coil.imageLoader
import com.gimy.tv.data.endpoint.EndpointResolver
import com.gimy.tv.domain.repository.VodRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次清掉 App 所有層級的快取，並重新探測站點網址。
 *
 * 分散在四個地方，任何一層沒清乾淨，使用者按重試就還是拿到同一份壞資料：
 *
 *  1. OkHttp 磁碟快取 — 網站回應。站方掛掉期間存進來的錯誤頁會一直被重放。
 *  2. Repository 記憶體快取 — 首頁列（5 分鐘）、搜尋與詳情（60 秒）。
 *  3. Coil 記憶體與磁碟快取 — 封面圖。
 *  4. EndpointResolver 選定的鏡像 — 存在 DataStore 且 TTL 24 小時，鏡像失效時
 *     不重新探測就永遠打同一個死網址。這一步會等探測跑完（有時間上限），
 *     好讓「清除快取後的第一次」就用到新網址。
 */
@Singleton
class CacheCleaner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val vodRepository: VodRepository,
    private val endpointResolver: EndpointResolver,
) {
    /**
     * @param reresolveEndpoints 是否順便重新探測鏡像。設定頁的「清除快取」與首頁重試都要，
     *        單純想丟掉圖片快取的場合可以關掉，省下探測的等待。
     * @return 是否在時間內等到探測結果；false 代表探測還在背景跑，下一次取用才會換網址。
     */
    suspend fun clearAll(reresolveEndpoints: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        // evictAll 會走磁碟，要在 IO 上跑；任何一層失敗都不該擋住其他層。
        runCatching { okHttpClient.cache?.evictAll() }
        runCatching { vodRepository.clearMemoryCaches() }
        runCatching {
            val loader = context.imageLoader
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
        }
        if (reresolveEndpoints) {
            runCatching { endpointResolver.awaitRefresh(ENDPOINT_REFRESH_BUDGET_MS) }.getOrDefault(false)
        } else {
            false
        }
    }

    companion object {
        /**
         * 等重新探測的時間上限。並行探測後整輪約等於最慢的來源（候選數 × PROBE_TIMEOUT_MS
         * 再加抓遠端設定），8 秒足夠涵蓋正常情況，又不會讓使用者盯著轉圈太久。
         */
        const val ENDPOINT_REFRESH_BUDGET_MS = 8_000L
    }
}
