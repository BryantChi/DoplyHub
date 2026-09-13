package com.gimy.tv.ui.browse

import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.StandardCategory
import com.gimy.tv.domain.model.categoryMap
import com.gimy.tv.domain.model.displayName
import com.gimy.tv.ui.category.categories
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 釘住分類編號與標題的對應。
 *
 * 為什麼需要：同一份對應曾經在五個地方各寫一次（CategoryScreen 的清單、BrowseViewModel 的
 * categoryNames、首頁 gimy 與 movieffm 的兩份分類列、HomeViewModel 的 ffmToGimyMap），
 * 改一處漏改另一處不會有任何編譯錯誤，只會在畫面上顯示成別的分類。2026-09-13 實際發生過：
 * 15 與 21 對調，點日劇顯示港劇、點港劇顯示日劇，而抓到的內容其實都正確。
 *
 * 現在全部改由 SiteCategoryMap 推導，這些測試守的是那張表本身。
 * 期望值以站台實際頁面標題為準（gimytv.me/type/N.html 的 <title>）。
 */
class CategoryNamesTest {

    @Test
    fun `日劇與港劇不得對調`() {
        val gimy = SourceType.GIMYTV.categoryMap
        assertThat(gimy.japanese).isEqualTo(15)
        assertThat(gimy.hk).isEqualTo(21)

        val ffm = SourceType.MOVIEFFM.categoryMap
        assertThat(ffm.japanese).isEqualTo(204)
        assertThat(ffm.hk).isEqualTo(208)
    }

    @Test
    fun `gimy 各分類對應站台實際名稱`() {
        val gimy = SourceType.GIMYTV.categoryMap
        assertThat(gimy.series).isEqualTo(2)
        assertThat(gimy.movie).isEqualTo(1)
        assertThat(gimy.anime).isEqualTo(4)
        assertThat(gimy.variety).isEqualTo(29)
        assertThat(gimy.chinese).isEqualTo(13)
        assertThat(gimy.korean).isEqualTo(20)
        assertThat(gimy.american).isEqualTo(16)
        assertThat(gimy.taiwan).isEqualTo(14)
        assertThat(gimy.documentary).isEqualTo(22)
    }

    /** 2026-09-13 實測 gimytv.me/type/30.html 回 404 頁面，留著只會誤導。 */
    @Test
    fun `站方已移除的 typeId 30 不應出現在 gimy 的表裡`() {
        val gimy = SourceType.GIMYTV.categoryMap
        val used = StandardCategory.entries.map { gimy.typeIdFor(it) }
        assertThat(used).doesNotContain(30)
    }

    /**
     * 分類畫面送出的 typeId 必須能被同一張表反查回同一個標題。
     * 這條是整個重構的核心保證：顯示與導航用的是同一份資料，不可能再對不上。
     */
    @Test
    fun `分類畫面的每一項都能反查回同一個標題`() {
        assertThat(categories).isNotEmpty()
        categories.forEach { item ->
            val source = SourceType.valueOf(item.sourceType)
            assertThat(browseTitleFor(source, item.typeId)).isEqualTo(item.name)
        }
    }

    /**
     * 同一個來源裡兩個分類共用一個 typeId 的話，categoryFor 反查會落到先比對到的那個，
     * 標題就會跟使用者點的分類對不上。
     */
    @Test
    fun `同一個來源的 typeId 不得重複`() {
        SourceType.entries.forEach { source ->
            val ids = StandardCategory.entries
                .map { source.categoryMap.typeIdFor(it) }
                .filter { it > 0 }
            assertThat(ids).containsNoDuplicates()
        }
    }

    /**
     * 首頁把 movieffm 的列插在同分類的 gimy 列後面。兩邊的編號必須真的指向同一個分類，
     * 否則 FFM 的日劇會被插在 gimy 的港劇下面。
     */
    @Test
    fun `movieffm 與 gimy 的分類要能一一對上`() {
        listOf(
            StandardCategory.JAPANESE to (204 to 15),
            StandardCategory.HK to (208 to 21),
            StandardCategory.TAIWAN to (207 to 14),
            StandardCategory.KOREAN to (201 to 20),
        ).forEach { (cat, expected) ->
            val (ffm, gimy) = expected
            assertThat(SourceType.MOVIEFFM.categoryMap.typeIdFor(cat)).isEqualTo(ffm)
            assertThat(SourceType.GIMYTV.categoryMap.typeIdFor(cat)).isEqualTo(gimy)
        }
    }

    /**
     * 顯示名稱以站台實際頁面標題為準。
     *
     * 收斂前 CategoryScreen 寫「電視劇」、HomeScreen 的同一個分類寫「劇集」——
     * 同一件事兩個名字，正是散落多處的副作用。
     */
    @Test
    fun `顯示名稱與站台頁面標題一致`() {
        assertThat(StandardCategory.SERIES.displayName).isEqualTo("電視劇")
        assertThat(StandardCategory.MOVIE.displayName).isEqualTo("電影")
        assertThat(StandardCategory.DOCUMENTARY.displayName).isEqualTo("紀錄片")
        assertThat(StandardCategory.VARIETY.displayName).isEqualTo("綜藝")
    }

    /** kubo 的韓劇是 24，舊的扁平表裡根本沒有這個編號，標題會 fallback 成「瀏覽」。 */
    @Test
    fun `非 gimy 來源的標題也要正確`() {
        assertThat(browseTitleFor(SourceType.KUBO123, 24)).isEqualTo("韓劇")
        assertThat(browseTitleFor(SourceType.GIMY_TW, 14)).isEqualTo("港劇")
        assertThat(browseTitleFor(SourceType.GIMYTV, 14)).isEqualTo("台劇")
        assertThat(StandardCategory.JAPANESE.displayName).isEqualTo("日劇")
    }
}
