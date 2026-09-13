package com.gimy.tv.data.repository

import com.gimy.tv.data.scraper.Forum5278Source
import com.gimy.tv.data.scraper.JableTvSource
import com.gimy.tv.data.scraper.XnxxSource
import com.gimy.tv.domain.model.PaginatedResult
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.domain.repository.AdultPlusCatalog
import javax.inject.Inject
import javax.inject.Singleton

/** 預設的 5278 版面，路徑鍵解析不出編號時用。 */
private const val DEFAULT_FORUM_ID = 23

@Singleton
class AdultPlusCatalogImpl @Inject constructor(
    private val jableSource: JableTvSource,
    private val xnxxSource: XnxxSource,
    private val forum5278Source: Forum5278Source,
) : AdultPlusCatalog {

    override suspend fun fetchByPath(
        sourceType: SourceType, pathKey: String, page: Int,
    ): PaginatedResult<Vod> = when (sourceType) {
        SourceType.JABLE_TV -> jableSource.fetchVodListByPath(pathKey, page)
        SourceType.XNXX -> xnxxSource.fetchVodListByPath(pathKey, page)
        SourceType.FORUM5278 -> forum5278Source.fetchVodList(
            pathKey.removePrefix("forum:").toIntOrNull() ?: DEFAULT_FORUM_ID, page,
        )
        // 其他來源不屬於進階區。回空頁而不是丟例外：呼叫端只是少一列，不該整個畫面炸掉。
        else -> PaginatedResult(emptyList(), page, 0, false)
    }
}
