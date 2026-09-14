package com.gimy.tv.data.repair

import com.gimy.tv.domain.model.RecoveryPlan
import com.gimy.tv.domain.model.RecoveryTarget

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
