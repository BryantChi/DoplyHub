package com.gimy.tv.data.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * CDN 下載網址的組裝。
 *
 * 會有這條路徑是因為 GitHub release asset 在台灣只有 89 KB/s，5.7MB 的 APK
 * 下載超過 40 秒，而舊版的 callTimeout 是 20 秒——使用者根本更新不了。
 * 同一支檔案走 jsDelivr 是 1,013 KB/s、5.9 秒。
 */
class DownloadMirrorTest {

    @Test
    fun `組出 jsDelivr 的網址`() {
        assertThat(DownloadMirror.cdnUrlFor("BryantChi/DoplyHub", "v3.1.15", "DoplyHub-v3.1.15.apk"))
            .isEqualTo("https://cdn.jsdelivr.net/gh/BryantChi/DoplyHub@v3.1.15/mov_app/v3.1.15/DoplyHub-v3.1.15.apk")
    }

    @Test
    fun `缺任何一段就回 null，讓呼叫端用原網址`() {
        assertThat(DownloadMirror.cdnUrlFor("", "v3.1.15", "a.apk")).isNull()
        assertThat(DownloadMirror.cdnUrlFor("BryantChi/DoplyHub", "", "a.apk")).isNull()
        assertThat(DownloadMirror.cdnUrlFor("BryantChi/DoplyHub", "v3.1.15", "")).isNull()
    }

    @Test
    fun `不是 apk 就不轉`() {
        assertThat(DownloadMirror.cdnUrlFor("BryantChi/DoplyHub", "v3.1.15", "notes.txt")).isNull()
    }

    @Test
    fun `repo 形狀不對就不轉`() {
        // 少了 owner，組出來只會 404
        assertThat(DownloadMirror.cdnUrlFor("DoplyHub", "v3.1.15", "a.apk")).isNull()
        assertThat(DownloadMirror.cdnUrlFor("a/b/c", "v3.1.15", "a.apk")).isNull()
    }
}
