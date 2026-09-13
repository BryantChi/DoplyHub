package com.gimy.tv.ui.browse

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 釘住分類 typeId 與標題的對應。
 *
 * 為什麼需要：同一份對應在三個地方各寫一次（CategoryScreen 的清單、各 source 的
 * fetchCategories、這裡的 categoryNames），改一處漏改另一處不會有任何編譯錯誤，
 * 只會在畫面上顯示成別的分類。2026-09-13 實際發生過：15 與 21 在標題表裡對調，
 * 點日劇顯示港劇、點港劇顯示日劇，而抓到的內容其實都正確——這種錯只有人眼看得出來。
 *
 * 期望值以站台實際頁面標題為準（gimytv.me/type/N.html 的 <title>）。
 */
class CategoryNamesTest {

    /** CategoryScreen 實際會送出的 typeId。這裡漏掉的話標題會 fallback 成「瀏覽」。 */
    private val typeIdsUsedByCategoryScreen = listOf(2, 1, 4, 29, 20, 13, 16, 15, 14, 21, 22)

    @Test
    fun `日劇與港劇不得對調`() {
        assertThat(categoryNames[15]).isEqualTo("日劇")
        assertThat(categoryNames[21]).isEqualTo("港劇")
    }

    @Test
    fun `分類畫面用到的每個 typeId 都要有標題`() {
        val missing = typeIdsUsedByCategoryScreen.filter { categoryNames[it] == null }
        assertThat(missing).isEmpty()
    }

    @Test
    fun `gimy 各分類對應站台實際名稱`() {
        assertThat(categoryNames[2]).isEqualTo("電視劇")
        assertThat(categoryNames[1]).isEqualTo("電影")
        assertThat(categoryNames[4]).isEqualTo("動漫")
        assertThat(categoryNames[29]).isEqualTo("綜藝")
        assertThat(categoryNames[13]).isEqualTo("陸劇")
        assertThat(categoryNames[20]).isEqualTo("韓劇")
        assertThat(categoryNames[16]).isEqualTo("美劇")
        assertThat(categoryNames[14]).isEqualTo("台劇")
        assertThat(categoryNames[22]).isEqualTo("紀錄片")
    }

    @Test
    fun `站方已移除的 typeId 30 不應留在表裡`() {
        // 2026-09-13 實測 gimytv.me/type/30.html 回 404 頁面，留著只會誤導。
        assertThat(categoryNames).doesNotContainKey(30)
    }
}
