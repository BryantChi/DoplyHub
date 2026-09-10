package com.gimy.tv.data.repair

import com.gimy.tv.domain.model.SourceType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 舊版存下來的 id 在新網域上開不起來時要怎麼處置。
 *
 * 這裡的重點不是「有沒有找到」，而是**分辨「這一筆確定沒了」與「只是連不上」**：
 * 兩者在呼叫端看起來都是一次失敗，但前者該標記失效讓使用者清掉，後者標了就是拿
 * 暫時性的網路問題去汙染使用者自己的資料。
 */
class RecoveryPlanTest {

    @Test
    fun `找到同一部片就改連到新的 id`() {
        val target = RecoveryTarget(SourceType.GIMYTV, 4321L)
        val plan = recoveryPlan(match = target, sourceAnswered = true)
        assertThat(plan).isEqualTo(RecoveryPlan.Relink(target))
    }

    @Test
    fun `來源有回應但查無此片，才算這一筆確定失效`() {
        val plan = recoveryPlan(match = null, sourceAnswered = true)
        assertThat(plan).isEqualTo(RecoveryPlan.MarkStale)
    }

    @Test
    fun `來源整個沒回應時不得標記失效`() {
        // 斷網、鏡像掛掉、站方擋 IP 都會走到這裡。標記失效等於用一次網路故障
        // 去判使用者的收藏死刑，所以只能什麼都不做。
        val plan = recoveryPlan(match = null, sourceAnswered = false)
        assertThat(plan).isEqualTo(RecoveryPlan.Inconclusive)
    }

    @Test
    fun `即使來源沒回應，只要找到替代片仍然改連過去`() {
        // 原來源連不上、但跨來源搜到同一部片：能開比什麼都重要。
        val target = RecoveryTarget(SourceType.MOVIEFFM, 99L)
        val plan = recoveryPlan(match = target, sourceAnswered = false)
        assertThat(plan).isEqualTo(RecoveryPlan.Relink(target))
    }
}
