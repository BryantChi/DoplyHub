package com.gimy.tv.data.scraper

import com.gimy.tv.data.endpoint.EndpointResolver
import com.gimy.tv.domain.model.SourceType
import okhttp3.OkHttpClient
import javax.inject.Inject

class GimyTvSource @Inject constructor(
    client: OkHttpClient,
    endpointResolver: EndpointResolver,
) : GimySource(client, endpointResolver) {
    override val sourceType = SourceType.GIMYTV
}
