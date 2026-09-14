package com.gimy.tv.domain.model

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
