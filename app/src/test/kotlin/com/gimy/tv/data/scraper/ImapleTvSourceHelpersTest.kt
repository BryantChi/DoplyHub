package com.gimy.tv.data.scraper

import com.gimy.tv.data.scraper.parser.ImapleLine
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.StandardCategory
import com.gimy.tv.domain.model.categoryMap
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/**
 * ImapleTvSource 中與網路無關的規則。來源本身要 OkHttp 與 EndpointResolver（需要 Android Context）
 * 才建得起來，所以會出錯的判斷都抽成頂層函式在這裡測。
 */
class ImapleTvSourceHelpersTest {
    private val base = "https://imaple.tv"
    private val map = SourceType.IMAPLE_TV.categoryMap

    /** 期望值以搬移前的演算法算出；改了就是 Jable／XNXX／Imaple 的既有收藏全部對不回 slug。 */
    @Test fun `stable id algorithm is unchanged`() {
        assertThat(slugStableId("ssis-001")).isEqualTo(4324561783251707987L)
        assertThat(slugStableId("cmucxzuk100bppihr26ey7d1c")).isEqualTo(766216315209902468L)
    }

    /** DetailViewModel 只對非網路錯誤呼叫 SavedEntryRecovery；丟成 IOException，舊收藏就永遠救不回來。 */
    @Test fun `a missing cuid is a scraper error, not an io error`() {
        val e = assertThrows(ScraperException::class.java) { cuidOrThrow(42L, null) }
        assertThat(e).isNotInstanceOf(IOException::class.java)
        assertThat(cuidOrThrow(42L, "c1")).isEqualTo("c1")
    }

    @Test fun `every standard category except adult has a path`() {
        StandardCategory.values().filter { it != StandardCategory.ADULT }.forEach { cat ->
            assertThat(imapleListUrl(base, map.typeIdFor(cat), 1)).startsWith("$base/")
        }
    }

    /** 已經帶篩選參數的路徑，翻頁要用 & 接，不能再接一個 ?。 */
    @Test fun `filtered category pages append pg with an ampersand`() {
        assertThat(imapleListUrl(base, map.typeIdFor(StandardCategory.KOREAN), 2)).isEqualTo("$base/series?area=kr&pg=2")
        assertThat(imapleListUrl(base, map.typeIdFor(StandardCategory.ANIME), 1)).isEqualTo("$base/anime?pg=1")
    }

    @Test fun `an unknown type id is rejected`() {
        assertThrows(ScraperException::class.java) { imapleListUrl(base, 99, 1) }
    }

    @Test fun `search keywords are trimmed and url encoded`() {
        assertThat(imapleSearchUrl(base, " 法醫 秦明&1 ", 2))
            .isEqualTo("$base/search?wd=%E6%B3%95%E9%86%AB+%E7%A7%A6%E6%98%8E%261&pg=2")
    }

    @Test fun `play url round trips the line key`() {
        val url = imaplePlayUrl("cmucxzuk100bppihr26ey7d1c", 3, "cj-ffzyapi-com.ffm3u8")
        assertThat(url).isEqualTo("/play/cmucxzuk100bppihr26ey7d1c/3?line=cj-ffzyapi-com.ffm3u8")
        assertThat(parseImaplePlayUrl(url)).isEqualTo(ImaplePlay("cmucxzuk100bppihr26ey7d1c", 3, "cj-ffzyapi-com.ffm3u8"))
    }

    @Test fun `an old maccms play url is not mistaken for a new one`() {
        assertThat(parseImaplePlayUrl("/vodplay/123-1-1.html")).isNull()
    }

    /** 有些來源的集名是「第 0 集」：集數一律依位置，名稱錯成 0 的改成位置編號。 */
    @Test fun `episodes are numbered by position and zero-numbered names are replaced`() {
        val groups = imapleEpisodeGroups(
            "c1",
            listOf(
                ImapleLine("非凡資源", "a.ffm3u8", listOf("第01集", "第02集")),
                ImapleLine("U酷資源", "b.ukm3u8", listOf("第 0 集", "第 0 集")),
            ),
        )
        assertThat(groups.map { it.sourceId }).containsExactly(1, 2).inOrder()
        assertThat(groups[1].episodes.map { it.number }).containsExactly(1, 2).inOrder()
        assertThat(groups[1].episodes.map { it.title }).containsExactly("第01集", "第02集").inOrder()
        assertThat(groups[1].episodes[1].playUrl).isEqualTo("/play/c1/2?line=b.ukm3u8")
    }

    @Test fun `a title without playable lines is an error, not an empty list`() {
        assertThrows(ScraperException::class.java) { imapleEpisodeGroups("c1", emptyList()) }
    }
}
