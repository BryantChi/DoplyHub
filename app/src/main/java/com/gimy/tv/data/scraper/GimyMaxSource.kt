package com.gimy.tv.data.scraper

import com.gimy.tv.data.endpoint.EndpointResolver
import com.gimy.tv.domain.model.SourceType
import okhttp3.OkHttpClient
import javax.inject.Inject

/** gitube.tv 跑的是跟 gimytv.me 一樣的模板，差別只在路徑 token——都在 profile 裡。 */
class GimyMaxSource @Inject constructor(
    client: OkHttpClient,
    endpointResolver: EndpointResolver,
) : GimySource(client, endpointResolver) {
    override val sourceType = SourceType.GIMYMAX
}
