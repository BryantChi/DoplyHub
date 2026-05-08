package com.gimy.tv.data.scraper

import com.gimy.tv.data.endpoint.EndpointResolver
import com.gimy.tv.domain.model.SourceType
import okhttp3.OkHttpClient
import java.net.URLEncoder
import javax.inject.Inject

class MomovodSource @Inject constructor(
    client: OkHttpClient,
    endpointResolver: EndpointResolver,
) : MacCmsListBasedSource(client, endpointResolver) {
    override val sourceType = SourceType.MOMOVOD
    override val detailUrlPath = "/vod"
    override val playUrlPath = "/play"

    override fun buildListUrl(typeId: Int, page: Int): String =
        "$baseUrl/show/$typeId--------$page---.html"

    /** Path-based search: /search/{keyword}.html (verified 200 OK) */
    override fun buildSearchUrl(keyword: String, page: Int): String =
        "$baseUrl/search/${URLEncoder.encode(keyword, "UTF-8")}.html"
}
