package com.gimy.tv.data.scraper

import com.gimy.tv.domain.model.SourceType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Gimy 系列的搜尋改走 ajax/suggest 這個 JSON 端點。
 *
 * 為什麼要換：站方在 Cloudflare 上掛的 WAF 規則是看 **query string 有沒有 wd 參數**
 * ——`/type/20.html` 通、`/type/20.html?wd=恐怖` 就回 Managed Challenge。所以把
 * 關鍵字放進 POST body 就完全不會觸發，不必再靠 WebView 解 cf_clearance。
 *
 * 下面的 JSON 是 2026-09-11 從 gimytv.me 實際取回的回應（截短）。
 */
class GimySuggestSearchTest {

    private val realResponse = """
        {"code":1,"msg":"數據列表","page":1,"pagecount":14,"limit":20,"total":274,"list":[
          {"id":484337,"name":"恐怖房間","en":"kongbufangjian","pic":"https://imgs.1777cdn.com/upload/vod/20260902-1/e6eb19f81b0d7556bfa00462cedfc0ea.jpg"},
          {"id":481494,"name":"墊底恐怖副本被我妝造成頂流","en":"diandikongbufuben","pic":"https://imgs.1777cdn.com/upload/vod/20260811-1/09d435900af7f4fde98891ad5ca9c1e7.jpg"}
        ]}
    """.trimIndent()

    @Test
    fun `解析出片名、封面與可直接組詳情網址的 id`() {
        val items = parseGimySuggest(realResponse, SourceType.GIMYTV)

        assertThat(items).hasSize(2)
        assertThat(items[0].id).isEqualTo(484337L)
        assertThat(items[0].title).isEqualTo("恐怖房間")
        assertThat(items[0].coverUrl).endsWith("e6eb19f81b0d7556bfa00462cedfc0ea.jpg")
        assertThat(items[0].sourceType).isEqualTo(SourceType.GIMYTV)
    }

    @Test
    fun `gitube 多出來的欄位不影響解析`() {
        // gitube.tv 的回應每筆多一個 type_is_vip_exclusive，解析不能因此炸掉。
        val withExtra = """
            {"code":1,"msg":"數據列表","list":[
              {"id":468096,"name":"貧道用中式恐怖嚇哭全球","en":"x","pic":"https://cdn.picsu.pics/a.jpg","type_is_vip_exclusive":0}
            ]}
        """.trimIndent()

        val items = parseGimySuggest(withExtra, SourceType.GIMYMAX)

        assertThat(items).hasSize(1)
        assertThat(items[0].id).isEqualTo(468096L)
        assertThat(items[0].sourceType).isEqualTo(SourceType.GIMYMAX)
    }

    @Test
    fun `查無結果回空清單而不是丟例外`() {
        // 站方對某些完整片名確實查不到（例如帶全形冒號的長片名），這是正常結果。
        val empty = """{"code":1,"msg":"數據列表","page":1,"pagecount":0,"limit":20,"total":0,"list":[]}"""

        assertThat(parseGimySuggest(empty, SourceType.GIMYTV)).isEmpty()
    }

    @Test
    fun `壞掉的回應回空清單，不讓整個聚合搜尋炸掉`() {
        assertThat(parseGimySuggest("<html>Just a moment...</html>", SourceType.GIMYTV)).isEmpty()
    }

    @Test
    fun `缺 id 或片名的項目直接略過`() {
        val partial = """
            {"list":[
              {"name":"沒有 id","pic":"https://x/a.jpg"},
              {"id":1,"name":"","pic":"https://x/b.jpg"},
              {"id":2,"name":"正常","pic":"https://x/c.jpg"}
            ]}
        """.trimIndent()

        val items = parseGimySuggest(partial, SourceType.GIMYTV)

        assertThat(items.map { it.title }).containsExactly("正常")
    }

    @Test
    fun `關鍵字放在 body、query string 不得出現 wd`() {
        // 這條是整個修正的關鍵：wd 一旦出現在 query string 就會被 WAF 攔下。
        val form = gimySuggestForm("恐怖 片", limit = 40)

        assertThat(form).contains("wd=")
        assertThat(form).contains("mid=1")
        assertThat(form).contains("limit=40")
        assertThat(form).doesNotContain(" ")   // 空白必須編碼
    }
}
