package com.gimy.tv.data.repair

import com.gimy.tv.domain.model.SourceType

/** 復原後要改連到的那一筆。跨來源找回來時 [sourceType] 會與原本不同。 */
data class RecoveryTarget(val sourceType: SourceType, val vodId: Long)

/** 舊記錄開不起來之後要做的事。 */
sealed interface RecoveryPlan {
    /** 找到同一部片，把記錄改連過去。 */
    data class Relink(val target: RecoveryTarget) : RecoveryPlan

    /** 來源有回應但查無此片：這一筆確定失效，計入 miss 讓使用者能一鍵清掉。 */
    data object MarkStale : RecoveryPlan

    /** 判斷不出來（來源根本沒回應），維持原狀。 */
    data object Inconclusive : RecoveryPlan
}

/**
 * 依「有沒有找到替代片」與「原來源這次有沒有回應」決定處置。
 *
 * [sourceAnswered] 是關鍵：查無此片與連不上，在呼叫端看起來都只是一次失敗，但只有
 * 前者能證明這一筆真的沒了。把後者也標成失效，等於拿一次網路故障去汙染使用者自己
 * 累積的收藏與紀錄。
 */
internal fun recoveryPlan(match: RecoveryTarget?, sourceAnswered: Boolean): RecoveryPlan = when {
    match != null -> RecoveryPlan.Relink(match)
    sourceAnswered -> RecoveryPlan.MarkStale
    else -> RecoveryPlan.Inconclusive
}
