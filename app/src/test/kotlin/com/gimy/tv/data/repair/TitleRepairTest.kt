package com.gimy.tv.data.repair

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The repair rewrites titles inside the user's own favourites and history, so the bar for
 * overwriting is high: a failed fetch must leave the existing row untouched rather than
 * replacing a wrong-but-readable title with "Unknown" or an empty string.
 */
class TitleRepairTest {

    @Test fun `replaces a stale title with a freshly parsed one`() {
        assertThat(shouldReplaceTitle(old = "MIMK-253 wrong", fetched = "MNGS-064 right")).isTrue()
    }

    @Test fun `a failed fetch never overwrites`() {
        assertThat(shouldReplaceTitle(old = "MIMK-253 wrong", fetched = null)).isFalse()
    }

    @Test fun `a blank result never overwrites`() {
        assertThat(shouldReplaceTitle(old = "MIMK-253 wrong", fetched = "   ")).isFalse()
    }

    /** "Unknown" is the parser's own giving-up value — writing it back loses information. */
    @Test fun `the parser fallback value never overwrites`() {
        assertThat(shouldReplaceTitle(old = "MIMK-253 wrong", fetched = "Unknown")).isFalse()
    }

    @Test fun `an unchanged title is not rewritten`() {
        assertThat(shouldReplaceTitle(old = "MNGS-064 right", fetched = "MNGS-064 right")).isFalse()
    }

    /** Whitespace-only differences are not worth a database write. */
    @Test fun `whitespace-only differences are ignored`() {
        assertThat(shouldReplaceTitle(old = "MNGS-064 right", fetched = "  MNGS-064 right  ")).isFalse()
    }

    /**
     * 修復每跑一次就是整批序列請求（每筆間隔 1.2 秒），而且是在冷啟動、跟首頁搶連線額度的時候。
     * 「有失敗就重跑」如果沒有上限，站台永久連不上或 slug 永遠補不齊時就會每次開 App 都重來一遍。
     */
    @Test fun `已完成就不再重跑`() {
        assertThat(shouldAttemptRepair(done = true, attempts = 0)).isFalse()
    }

    @Test fun `暫時性失敗還留有重試機會`() {
        assertThat(shouldAttemptRepair(done = false, attempts = 0)).isTrue()
        assertThat(shouldAttemptRepair(done = false, attempts = MAX_REPAIR_ATTEMPTS - 1)).isTrue()
    }

    @Test fun `達到嘗試上限後停止重跑`() {
        assertThat(shouldAttemptRepair(done = false, attempts = MAX_REPAIR_ATTEMPTS)).isFalse()
    }
}
