package com.gimy.tv.data.endpoint

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

/**
 * 探測預算必須由 OkHttp 自己的計時器執行。
 *
 * 專案裡所有抓取都是阻塞的 execute()，coroutine 的 withTimeout 在這種呼叫上攔不住任何東西，
 * 所以「3 秒探測預算」若沒有真的套上去，實際等的是 client 的 callTimeout。
 * 這裡用一個「接受連線但永遠不回應」的 socket 重現那個情境：預算失效的話，
 * 這個呼叫會一路等到 client 層級的上限才回來。
 */
class ProbeBudgetTest {

    @Test
    fun `預算會中斷沒有回應的連線`() {
        ServerSocket(0).use { server ->
            // 只 accept、不寫任何位元組，讓連線就這樣掛著。
            Thread { runCatching { server.accept() } }.apply { isDaemon = true }.start()

            val client = OkHttpClient.Builder()
                .callTimeout(20, TimeUnit.SECONDS)  // 與主 client 相同的上限
                .build()
            val req = Request.Builder().url("http://127.0.0.1:${server.localPort}/").get().build()

            var result: Result<Unit>
            val elapsed = measureTimeMillis {
                result = runCatching { client.newCall(req).withBudget(300L).execute().close() }
            }

            assertThat(result.isFailure).isTrue()
            // 沒套上預算的話，這裡會等 OkHttp 預設的 read timeout（10 秒）或 callTimeout（20 秒）。
            assertThat(elapsed).isLessThan(3_000L)
        }
    }
}
