package com.gimy.tv.data.repository

import com.gimy.tv.data.local.dao.VodCacheDao
import com.gimy.tv.data.scraper.GimyMaxSource
import com.gimy.tv.data.scraper.GimyTvSource
import com.gimy.tv.data.scraper.SiteSource
import com.gimy.tv.domain.model.*
import com.gimy.tv.domain.repository.VodRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VodRepositoryImpl @Inject constructor(
    private val gimyMaxSource: GimyMaxSource,
    private val gimyTvSource: GimyTvSource,
    private val vodCacheDao: VodCacheDao
) : VodRepository {

    private fun getSource(sourceType: SourceType): SiteSource = when (sourceType) {
        SourceType.GIMYMAX -> gimyMaxSource
        SourceType.GIMYTV -> gimyTvSource
    }

    override suspend fun getCategories(sourceType: SourceType): List<Category> {
        return getSource(sourceType).fetchCategories()
    }

    override suspend fun getVodList(
        sourceType: SourceType,
        typeId: Int,
        page: Int
    ): PaginatedResult<Vod> {
        return getSource(sourceType).fetchVodList(typeId, page)
    }

    override suspend fun getVodDetail(sourceType: SourceType, vodId: Long): VodDetail {
        return getSource(sourceType).fetchVodDetail(vodId)
    }

    override suspend fun getPlayerData(sourceType: SourceType, episodeUrl: String): PlayerData {
        return getSource(sourceType).fetchPlayerData(episodeUrl)
    }

    override suspend fun search(
        sourceType: SourceType,
        keyword: String,
        page: Int
    ): PaginatedResult<Vod> {
        return getSource(sourceType).search(keyword, page)
    }
}
