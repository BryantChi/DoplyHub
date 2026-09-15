package com.gimy.tv.data.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 更新對話框的說明文字。
 *
 * 3.1.15 發版後實機上直接顯示出「## 新功能」「**TV 播放中可直接換集、換線**」——
 * release body 是 markdown，而對話框是純 Text，語法就這樣露給使用者看。
 */
class ChangelogTest {

    @Test
    fun `標題的井字號要拿掉`() {
        assertThat(plainChangelog("## 新功能")).isEqualTo("新功能")
        assertThat(plainChangelog("# 大標")).isEqualTo("大標")
        assertThat(plainChangelog("#### 小標")).isEqualTo("小標")
    }

    @Test
    fun `粗體與斜體的星號要拿掉`() {
        assertThat(plainChangelog("**TV 播放中可直接換集、換線**")).isEqualTo("TV 播放中可直接換集、換線")
        assertThat(plainChangelog("__重點__")).isEqualTo("重點")
        assertThat(plainChangelog("*斜體*")).isEqualTo("斜體")
    }

    @Test
    fun `清單符號要拿掉`() {
        assertThat(plainChangelog("- 修好了 A\n- 修好了 B")).isEqualTo("修好了 A\n修好了 B")
        assertThat(plainChangelog("* 項目")).isEqualTo("項目")
    }

    @Test
    fun `行內程式碼與連結只留文字`() {
        assertThat(plainChangelog("修正 `PlayerView` 吃掉按鍵")).isEqualTo("修正 PlayerView 吃掉按鍵")
        assertThat(plainChangelog("見 [說明頁](https://example.com)")).isEqualTo("見 說明頁")
    }

    @Test
    fun `水平線移除，連續空行收成一行`() {
        assertThat(plainChangelog("A\n\n---\n\n\nB")).isEqualTo("A\n\nB")
    }

    @Test
    fun `純文字原樣保留`() {
        val plain = "這一版修好了很多問題。\n\n電視遙控器在播放中常常沒反應\n按 HOME 後影片還在背景跑"
        assertThat(plainChangelog(plain)).isEqualTo(plain)
    }

    @Test
    fun `片名裡的星號與底線不該被當成語法吃掉`() {
        assertThat(plainChangelog("2 * 3 = 6")).isEqualTo("2 * 3 = 6")
        assertThat(plainChangelog("檔名 my_file_name 不變")).isEqualTo("檔名 my_file_name 不變")
    }

    @Test
    fun `完整的 release body`() {
        val body = """
            完成修復。

            ## 新功能

            **TV 播放中可直接換集、換線**

            以前得退回詳情頁。

            ## 修復

            - 遙控器沒反應
            - 背景仍在解碼
        """.trimIndent()
        val out = plainChangelog(body)
        assertThat(out).doesNotContain("#")
        assertThat(out).doesNotContain("**")
        assertThat(out).contains("新功能")
        assertThat(out).contains("TV 播放中可直接換集、換線")
        assertThat(out).contains("遙控器沒反應")
    }
}
