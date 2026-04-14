package com.gimy.tv.data.repository

import com.gimy.tv.data.local.dao.WatchHistoryDao
import com.gimy.tv.data.local.entity.WatchHistoryEntity
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.repository.WatchHistoryEntry
import com.gimy.tv.domain.repository.WatchHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchHistoryRepositoryImpl @Inject constructor(
    private val dao: WatchHistoryDao
) : WatchHistoryRepository {

    override fun getRecentHistory(limit: Int): Flow<List<WatchHistoryEntry>> {
        return dao.getRecent(limit).map { entities ->
            entities.map { it.toEntry() }
        }
    }

    override suspend fun getProgress(vodId: Long, sourceType: SourceType): WatchHistoryEntry? {
        return dao.getByVod(vodId, sourceType.name)?.toEntry()
    }

    override suspend fun saveProgress(entry: WatchHistoryEntry) {
        val existing = dao.getByVod(entry.vodId, entry.sourceType.name)
        dao.upsert(
            WatchHistoryEntity(
                id = existing?.id ?: 0,
                vodId = entry.vodId,
                sourceType = entry.sourceType.name,
                title = entry.title,
                coverUrl = entry.coverUrl,
                episodeNum = entry.episodeNum,
                episodeTitle = entry.episodeTitle,
                sourceId = entry.sourceId,
                positionMs = entry.positionMs,
                durationMs = entry.durationMs
            )
        )
    }

    override suspend fun deleteEntry(vodId: Long, sourceType: SourceType) {
        dao.deleteByVod(vodId, sourceType.name)
    }

    override suspend fun clearHistory() {
        dao.deleteAll()
    }

    private fun WatchHistoryEntity.toEntry() = WatchHistoryEntry(
        vodId = vodId,
        sourceType = SourceType.valueOf(sourceType),
        title = title,
        coverUrl = coverUrl,
        episodeNum = episodeNum,
        episodeTitle = episodeTitle,
        sourceId = sourceId,
        positionMs = positionMs,
        durationMs = durationMs,
        updatedAt = updatedAt
    )
}
