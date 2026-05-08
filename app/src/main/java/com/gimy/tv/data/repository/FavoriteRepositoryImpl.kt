package com.gimy.tv.data.repository

import com.gimy.tv.data.local.dao.FavoriteDao
import com.gimy.tv.data.local.entity.FavoriteEntity
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.domain.repository.FavoriteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoriteRepositoryImpl @Inject constructor(
    private val dao: FavoriteDao
) : FavoriteRepository {

    /** Sources whose entire content is treated as adult — used to auto-tag isAdult on insert. */
    private val adultOnlySources = setOf(SourceType.JABLE_TV, SourceType.XNXX, SourceType.FORUM5278)

    override fun getFavorites(): Flow<List<Vod>> {
        return dao.getAll().map { entities ->
            entities.map { it.toVod() }
        }
    }

    override fun getAdultFavorites(): Flow<List<Vod>> {
        return dao.getAllAdult().map { entities ->
            entities.map { it.toVod() }
        }
    }

    override fun isFavorite(vodId: Long, sourceType: SourceType): Flow<Boolean> {
        return dao.isFavorite(vodId, sourceType.name)
    }

    override suspend fun addFavorite(vod: Vod) {
        dao.insert(
            FavoriteEntity(
                vodId = vod.id,
                sourceType = vod.sourceType.name,
                title = vod.title,
                coverUrl = vod.coverUrl,
                category = vod.category,
                year = vod.year,
                status = vod.status,
                isAdult = vod.sourceType in adultOnlySources,
            )
        )
    }

    override suspend fun removeFavorite(vodId: Long, sourceType: SourceType) {
        dao.delete(vodId, sourceType.name)
    }

    private fun FavoriteEntity.toVod() = Vod(
        id = vodId,
        sourceType = runCatching { SourceType.valueOf(sourceType) }.getOrDefault(SourceType.GIMYTV),
        title = title,
        coverUrl = coverUrl,
        category = category,
        year = year,
        status = status
    )
}
