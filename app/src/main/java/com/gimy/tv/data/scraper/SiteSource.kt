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

    /**
     * Probe the given baseUrl by fetching its default-category list page (page 1) and
     * parsing it with THIS source's parser; returns the number of items parsed.
     * Health-probe use — decoupled from [baseUrl]/EndpointResolver (does NOT call getBaseUrl).
     * Default -1 means "probe unsupported" → callers fall back to a HEAD 200 check.
     */
    suspend fun probeListCount(baseUrl: String): Int = -1
}
