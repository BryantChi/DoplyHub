package com.gimy.tv.data.scraper

import com.gimy.tv.data.endpoint.EndpointResolver
import com.gimy.tv.domain.model.SourceType
import okhttp3.OkHttpClient
import javax.inject.Inject

class EynyTvSource @Inject constructor(
    client: OkHttpClient,
    endpointResolver: EndpointResolver,
) : MacCmsListBasedSource(client, endpointResolver) {
    override val sourceType = SourceType.EYNY_TV
    override val detailUrlPath = "/voddetail"
    override val playUrlPath = "/vodplay"
}
