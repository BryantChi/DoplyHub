package com.gimy.tv.data.repository

import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 聚合搜尋的跨來源合併。
 *
 * 原本同一部片只保留最先出現的那個來源，後面的直接丟掉——結果是排在後面的來源
 * （Eyny 排第 5）幾乎永遠不會出現在來源篩選裡，即使它確實有那部片。實測「恐怖」時
 * Eyny 回的 10 筆全部被前四個來源蓋掉，使用者完全看不到 Eyny 這個選項。
 *
 * 改成：同一部片仍然只出一張卡，但把「還有哪些來源也有它、各自的 vodId 是多少」記在
 * 卡片上，來源篩選才能列出它，點下去也才開得到那個來源的版本。
 */
class SearchResultMergeTest {

    private fun vod(id: Long, source: SourceType, title: String) =
        @Suppress("DEPRECATION") Vod(id, source, title, "", "", 0, "")

    /** 測試用的 key：直接用片名，把「同一部片」的判定與合併行為分開測。 */
    private val byTitle: (Vod) -> Any = { it.title }

    @Test
    fun `重複的片保留第一張卡，並記下後來的來源與其 id`() {
        val primary = listOf(vod(1, SourceType.GIMYTV, "恐怖大師"))
        val secondary = listOf(vod(999, SourceType.EYNY_TV, "恐怖大師"))

        val merged = mergeSearchResults(primary, secondary, byTitle)

        assertThat(merged).hasSize(1)
        assertThat(merged[0].id).isEqualTo(1L)
        assertThat(merged[0].sourceType).isEqualTo(SourceType.GIMYTV)
        assertThat(merged[0].altSources).containsExactly(SourceType.EYNY_TV, 999L)
    }

    @Test
    fun `沒重複的片正常加進來，順序接在後面`() {
        val primary = listOf(vod(1, SourceType.GIMYTV, "甲"))
        val secondary = listOf(vod(2, SourceType.EYNY_TV, "乙"))

        val merged = mergeSearchResults(primary, secondary, byTitle)

        assertThat(merged.map { it.title }).containsExactly("甲", "乙").inOrder()
        assertThat(merged[1].altSources).isEmpty()
    }

    @Test
    fun `三個來源都有同一部片時全部記下來`() {
        var merged = mergeSearchResults(
            listOf(vod(1, SourceType.GIMYTV, "同片")),
            listOf(vod(2, SourceType.IMAPLE_TV, "同片")),
            byTitle,
        )
        merged = mergeSearchResults(merged, listOf(vod(3, SourceType.EYNY_TV, "同片")), byTitle)

        assertThat(merged).hasSize(1)
        assertThat(merged[0].altSources)
            .containsExactly(SourceType.IMAPLE_TV, 2L, SourceType.EYNY_TV, 3L)
    }

    @Test
    fun `同一個來源內部重複時只記一次，不覆蓋先出現的 id`() {
        val merged = mergeSearchResults(
            listOf(vod(1, SourceType.GIMYTV, "同片")),
            listOf(vod(2, SourceType.EYNY_TV, "同片"), vod(3, SourceType.EYNY_TV, "同片")),
            byTitle,
        )

        assertThat(merged[0].altSources).containsExactly(SourceType.EYNY_TV, 2L)
    }

    @Test
    fun `主要清單自己重複時不會把自己記成替代來源`() {
        // 同一個來源回了兩筆同名（站方資料本來就可能這樣），不該讓卡片說「我自己也有」。
        val merged = mergeSearchResults(
            listOf(vod(1, SourceType.GIMYTV, "同片"), vod(2, SourceType.GIMYTV, "同片")),
            emptyList(),
            byTitle,
        )

        assertThat(merged).hasSize(2)
        assertThat(merged[0].altSources).isEmpty()
    }
}
