package com.gimy.tv.data.scraper

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

/**
 * 取流預算必須真的傳到 OkHttp。
 *
 * 上層宣告「每條線路 8 秒」，但 coroutine 的 withTimeout 中斷不了阻塞的 execute()；
 * 秒數若沒套在 call 上，一條卡住的線路會等滿網路層 20 秒的絕對上限，
 * 五條線路輪下來就是 100 秒的「正在連接…」。
 */
class ScraperHttpTest {

    @Test
    fun `還沒到截止時間就回剩餘毫秒`() {
        val remaining = remainingBudget(System.currentTimeMillis() + 5_000)
        assertThat(remaining).isNotNull()
        assertThat(remaining!!).isGreaterThan(4_000L)
        assertThat(remaining).isAtMost(5_000L)
    }

    /** OkHttp 把 0 解讀成「不設限」，剛好與我們要表達的意思相反，所以下限是 1。 */
    @Test
    fun `已經超過截止時間回 1 而不是 0 或負數`() {
        assertThat(remainingBudget(System.currentTimeMillis() - 10_000)).isEqualTo(1L)
    }

    @Test
    fun `沒有截止時間就不設限`() {
        assertThat(remainingBudget(null)).isNull()
    }

    @Test
    fun `預算會中斷沒有回應的來源`() {
        ServerSocket(0).use { server ->
            // 只 accept、不回任何位元組，模擬「連得上但不回應」的線路。
            Thread { runCatching { server.accept() } }.apply { isDaemon = true }.start()

            val client = OkHttpClient.Builder()
                .callTimeout(20, TimeUnit.SECONDS)  // 與主 client 相同的絕對上限
                .build()

            var result: Result<String>
            val elapsed = measureTimeMillis {
                result = runCatching {
                    client.fetchHtml("http://127.0.0.1:${server.localPort}/", budgetMs = 300L)
                }
            }

            assertThat(result.isFailure).isTrue()
            // 沒把預算套上去的話，這裡會等 OkHttp 預設的 read timeout（10 秒）或 callTimeout（20 秒）。
            assertThat(elapsed).isLessThan(3_000L)
        }
    }
}
