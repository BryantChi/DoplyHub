package com.gimy.tv.data.scraper

import com.gimy.tv.data.endpoint.EndpointResolver
import com.gimy.tv.domain.model.SourceType
import okhttp3.OkHttpClient
import javax.inject.Inject

class MomovodSource @Inject constructor(
    client: OkHttpClient,
    endpointResolver: EndpointResolver,
) : MacCmsListBasedSource(client, endpointResolver) {
    override val sourceType = SourceType.MOMOVOD
    override val detailUrlPath = "/vod"
    override val playUrlPath = "/play"
    override val listUrlPath = "/type"   // momovod uses /type/{id}.html, not /vodtype/
    // Same renaming applies to search: /search.html?wd=, not /vodsearch/.
    // 不要改回 /search/{kw}.html：那個網址同樣回 200，但站方會丟掉關鍵字（頁面標題變成
    // 「影片“”的搜索結果」），回的是預設內容。2026-10-03 實測「愛」：該格式 21 筆只有 1 筆
    // 含「愛」，/search.html?wd= 有 16 筆。驗搜尋要看結果，不能只看狀態碼。
    override val searchStyle = MacCmsSearchStyle.SEARCH_HTML
}
