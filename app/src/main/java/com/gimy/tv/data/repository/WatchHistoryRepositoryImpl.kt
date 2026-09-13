package com.gimy.tv.data.repository

import com.gimy.tv.data.local.dao.WatchHistoryDao
import com.gimy.tv.data.local.entity.WatchHistoryEntity
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.isAdultOnly
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

    override fun getRecentAdultHistory(limit: Int): Flow<List<WatchHistoryEntry>> {
        return dao.getRecentAdult(limit).map { entities ->
            entities.map { it.toEntry() }
        }
    }

    override suspend fun getProgress(vodId: Long, sourceType: SourceType): WatchHistoryEntry? {
        return dao.getByVod(vodId, sourceType.name)?.toEntry()
    }

    override fun observeProgress(vodId: Long, sourceType: SourceType): Flow<WatchHistoryEntry?> {
        return dao.observeByVod(vodId, sourceType.name).map { it?.toEntry() }
    }

    override suspend fun saveProgress(entry: WatchHistoryEntry) {
        // 交給 DAO 的 @Transaction 版本：先查後寫這段必須是原子的，否則退出播放器時
        // 同時發出的幾筆存檔會各自讀到 null，插出重複列。
        dao.upsertByVod(
            WatchHistoryEntity(
                id = 0,
                vodId = entry.vodId,
                sourceType = entry.sourceType.name,
                title = entry.title,
                coverUrl = entry.coverUrl,
                episodeNum = entry.episodeNum,
                episodeTitle = entry.episodeTitle,
                sourceId = entry.sourceId,
                positionMs = entry.positionMs,
                durationMs = entry.durationMs,
                isAdult = entry.sourceType.isAdultOnly,
                // Only record playedSourceType when it differs from primary —
                // null is the "no special routing needed" marker. Saves a DB
                // string + simplifies the read path's null check.
                playedSourceType = entry.playedSourceType
                    ?.takeIf { it != entry.sourceType }
                    ?.name,
                episodeKind = entry.episodeKind,
            )
        )
    }

    override suspend fun deleteEntry(vodId: Long, sourceType: SourceType) {
        dao.deleteByVod(vodId, sourceType.name)
    }

    override suspend fun clearHistory() {
        dao.deleteAll()
    }

    override suspend fun clearAdultHistory() {
        dao.deleteAllAdult()
    }

    private fun WatchHistoryEntity.toEntry() = WatchHistoryEntry(
        vodId = vodId,
        sourceType = runCatching { SourceType.valueOf(sourceType) }.getOrDefault(SourceType.GIMYTV),
        title = title,
        coverUrl = coverUrl,
        episodeNum = episodeNum,
        episodeTitle = episodeTitle,
        sourceId = sourceId,
        positionMs = positionMs,
        durationMs = durationMs,
        updatedAt = updatedAt,
        playedSourceType = playedSourceType
            ?.let { name -> runCatching { SourceType.valueOf(name) }.getOrNull() },
        episodeKind = episodeKind,
    )
}
