package com.gimy.tv.data.repair

import com.gimy.tv.data.cleanup.StaleEntryTracker
import com.gimy.tv.data.local.dao.FavoriteDao
import com.gimy.tv.data.local.dao.WatchHistoryDao
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.repository.TitleLookup
import com.gimy.tv.domain.repository.VodRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 收藏／觀看紀錄開不起來時，用存著的片名把同一部片找回來。
 *
 * 為什麼需要：這些記錄存的是 `(vodId, sourceType)`，不是網址，而每個鏡像網域有**各自
 * 獨立的 id 空間**。v3.1.0 把 GIMYTV 從 gimyplus.com 換到 gimytv.me、GIMYMAX 從
 * gimy01.co 換到 gitube.tv 之後，舊版存下來的 id 在新站上不是 404 就是指到另一部片。
 * 症狀正好只出現在「從舊版更新上來」的機器上。
 *
 * 片名是跨網域唯一還通用的識別，所以拿它重新對回去。找不到時不會亂刪東西——只在
 * 來源確實有回應（證明不是網路問題）時把該筆標為失效，實際刪除交給使用者按
 * 「清除失效 N 筆」。
 */
@Singleton
class SavedEntryRecovery @Inject constructor(
    private val vodRepository: VodRepository,
    private val favoriteDao: FavoriteDao,
    private val watchHistoryDao: WatchHistoryDao,
    private val staleEntryTracker: StaleEntryTracker,
) {
    /**
     * 嘗試復原 [sourceType] / [vodId] 這一筆。
     *
     * 只處理「使用者存過」的項目：隨手點進來的片沒有記錄可修，也沒有東西該被標記。
     */
    suspend fun recover(sourceType: SourceType, vodId: Long): RecoveryPlan {
        val title = savedTitleOf(sourceType, vodId) ?: return RecoveryPlan.Inconclusive

        val lookup = runCatching { vodRepository.findByTitle(title, sourceType) }
            .getOrDefault(TitleLookup(match = null, sourceAnswered = false))
        val target = lookup.match?.let { RecoveryTarget(it.sourceType, it.id) }
            // 找到的就是原本那一筆時不算復原，否則會原地繞回同一個開不起來的 id。
            ?.takeIf { it.sourceType != sourceType || it.vodId != vodId }

        val plan = recoveryPlan(target, lookup.sourceAnswered)
        when (plan) {
            is RecoveryPlan.Relink -> relink(sourceType, vodId, plan.target)
            // 計數只在這裡做。呼叫端不要再自己記一次，否則同一次失敗會被算兩次，
            // 三次就自動移除的門檻等於被砍成一次半。
            RecoveryPlan.MarkStale ->
                runCatching { staleEntryTracker.recordOpenResult(sourceType, vodId, opened = false) }
            RecoveryPlan.Inconclusive -> Unit
        }
        return plan
    }

    /** 收藏優先——使用者主動收的東西，片名比觀看紀錄可靠（紀錄可能是舊解析寫壞的標題）。 */
    private suspend fun savedTitleOf(sourceType: SourceType, vodId: Long): String? {
        val source = sourceType.name
        val fromFavorite = runCatching { favoriteDao.getByVod(vodId, source)?.title }.getOrNull()
        if (!fromFavorite.isNullOrBlank()) return fromFavorite
        return runCatching { watchHistoryDao.getByVod(vodId, source)?.title }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * 把記錄改指到新的一筆，順便把 missCount 清掉（能開了就不該還標著失效）。
     *
     * 目標位置已經有一筆時改成刪掉舊的：那筆是使用者在新站上另外存的，帶著自己的
     * 播放進度，不該被舊記錄蓋掉。
     */
    private suspend fun relink(
        oldSourceType: SourceType,
        oldVodId: Long,
        target: RecoveryTarget,
    ) {
        val oldSource = oldSourceType.name
        val newSource = target.sourceType.name
        runCatching {
            if (favoriteDao.getByVod(target.vodId, newSource) != null) {
                favoriteDao.delete(oldVodId, oldSource)
            } else {
                favoriteDao.relink(oldVodId, oldSource, target.vodId, newSource)
            }
        }
        runCatching {
            if (watchHistoryDao.getByVod(target.vodId, newSource) != null) {
                watchHistoryDao.deleteStaleRow(oldVodId, oldSource)
            } else {
                watchHistoryDao.relink(oldVodId, oldSource, target.vodId, newSource)
            }
        }
    }
}
