package com.gimy.tv.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Eyny／Kubo 與 Imaple 是同一個資料庫，已併入 Imaple。它們若還留在啟用清單裡，聚合搜尋會把
 * 同一個 Imaple 查三次、每部片出現三份；設定頁也會冒出兩個沒有作用的開關。
 */
class ActiveSourceTypesTest {
    @Test fun `retired sources are excluded`() {
        assertThat(activeSourceTypes()).containsNoneOf(SourceType.EYNY_TV, SourceType.KUBO123)
    }

    /** 合併後的 Imaple 與其他來源都必須還在，只少退役的兩個。 */
    @Test fun `every other source stays active`() {
        assertThat(activeSourceTypes()).contains(SourceType.IMAPLE_TV)
        assertThat(activeSourceTypes()).hasSize(SourceType.values().size - 2)
    }
}
