package com.gimy.tv.data.scraper

import com.gimy.tv.domain.model.*

interface SiteSource {
    val sourceType: SourceType
    val baseUrl: String

    suspend fun fetchCategories(): List<Category>
    suspend fun fetchVodList(typeId: Int, page: Int): PaginatedResult<Vod>
    suspend fun fetchVodDetail(vodId: Long): VodDetail
    suspend fun fetchPlayerData(episodeUrl: String): PlayerData
    suspend fun search(keyword: String, page: Int): PaginatedResult<Vod>
}
