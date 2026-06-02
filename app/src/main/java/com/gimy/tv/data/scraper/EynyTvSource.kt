package com.gimy.tv.data.scraper

import com.gimy.tv.data.endpoint.EndpointResolver
import com.gimy.tv.data.scraper.parser.EynyTvParser
import com.gimy.tv.domain.model.PaginatedResult
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.domain.model.VodDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject

class EynyTvSource @Inject constructor(
    client: OkHttpClient,
    endpointResolver: EndpointResolver,
) : MacCmsListBasedSource(client, endpointResolver) {
    override val sourceType = SourceType.EYNY_TV
    override val detailUrlPath = "/voddetail"
    override val playUrlPath = "/vodplay"

    override suspend fun fetchVodList(typeId: Int, page: Int): PaginatedResult<Vod> =
        withContext(Dispatchers.IO) {
            EynyTvParser.parseVodList(fetchDocument(buildListUrl(typeId, page)), baseUrl, page)
        }

    override suspend fun fetchVodDetail(vodId: Long): VodDetail =
        withContext(Dispatchers.IO) {
            EynyTvParser.parseVodDetail(fetchDocument("$baseUrl$detailUrlPath/$vodId.html"), vodId, baseUrl)
        }

    override suspend fun search(keyword: String, page: Int): PaginatedResult<Vod> =
        withContext(Dispatchers.IO) {
            val doc = fetchDocument(buildSearchUrl(keyword, page))
            doc.select("#stickyside").remove()
            EynyTvParser.parseVodList(doc, baseUrl, page)
        }

    // eynytv.com uses the module template, NOT the base myui template. This source fully
    // bypasses the base parser (fetchVodList/Detail/search above all delegate to EynyTvParser),
    // so the probe must use EynyTvParser too — not the base's parseVodList. Keep both in sync.
    override suspend fun probeListCount(baseUrl: String): Int = withContext(Dispatchers.IO) {
        runCatching {
            EynyTvParser.parseVodList(fetchDocument("$baseUrl$listUrlPath/2.html"), baseUrl, 1).items.size
        }.getOrDefault(0)
    }
}
