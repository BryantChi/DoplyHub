package com.gimy.tv.data.repository

import com.google.common.truth.Truth.assertThat
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.isAdultOnly
import org.junit.Test

/**
 * 單一來源搜尋失敗時的備援規則。
 *
 * 這裡用 SourceType.entries 逐一檢查而不是列舉幾個代表值：新增來源時如果忘了歸類，
 * 測試會直接指出哪一個來源掉進錯的分支——這正是這條規則最容易出錯的時機。
 */
class SearchFallbackTest {

    @Test
    fun `成人來源失敗不退回一般片單`() {
        val adult = SourceType.entries.filter { it.isAdultOnly }
        assertThat(adult).containsExactly(
            SourceType.JABLE_TV, SourceType.XNXX, SourceType.FORUM5278
        )
        adult.forEach {
            assertThat(shouldFallbackToMovieffm(it)).isFalse()
        }
    }

    @Test
    fun `movieffm 自己失敗不會退回自己`() {
        assertThat(shouldFallbackToMovieffm(SourceType.MOVIEFFM)).isFalse()
    }

    @Test
    fun `一般來源失敗才退回 movieffm`() {
        val general = SourceType.entries.filter {
            it != SourceType.MOVIEFFM && !it.isAdultOnly
        }
        assertThat(general).isNotEmpty()
        general.forEach {
            assertThat(shouldFallbackToMovieffm(it)).isTrue()
        }
    }
}
