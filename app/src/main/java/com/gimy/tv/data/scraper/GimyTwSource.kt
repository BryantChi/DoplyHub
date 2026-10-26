package com.gimy.tv.data.scraper

import com.gimy.tv.data.endpoint.EndpointResolver
import com.gimy.tv.domain.model.SourceType
import okhttp3.OkHttpClient
import javax.inject.Inject

class GimyTwSource @Inject constructor(
    client: OkHttpClient,
    endpointResolver: EndpointResolver,
) : MacCmsListBasedSource(client, endpointResolver) {
    override val sourceType = SourceType.GIMY_TW
    override val detailUrlPath = "/voddetail"
    override val playUrlPath = "/vodplay"
    // 2026-10-26 起 /vodtype/{id}-{page}.html 永遠回第 1 頁；vodshow 分頁正常且保留分類（13 個分類逐一驗過）。
    override val listStyle = MacCmsListStyle.VODSHOW
}
