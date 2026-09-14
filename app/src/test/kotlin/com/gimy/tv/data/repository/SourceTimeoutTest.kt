package com.gimy.tv.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * [withSourceTimeout] 的 catch 順序。
 *
 * 稽核 C-9：並行抓取的 async 內寫成裸 `catch (e: Exception)`，把使用者離開畫面
 * 發出的 CancellationException 一起吞掉，那批八個站的抓取仍然跑完，跟首頁重載
 * 搶同一組並行額度。
 *
 * 但「先 rethrow CancellationException」會壞另一邊：TimeoutCancellationException
 * 繼承自它，逾時也會被 rethrow，一個來源慢就把整個 coroutineScope 拖垮。
 * 兩種寫錯的方式各壞一邊，所以兩個方向都要測。
 */
class SourceTimeoutTest {

    @Test
    fun `逾時當成這個來源失敗，不往外拋`() = runBlocking {
        val result = withSourceTimeout(50, onFailure = { "failed" }) {
            delay(5_000)
            "never"
        }
        assertThat(result).isEqualTo("failed")
    }

    @Test
    fun `一般例外當成這個來源失敗`() = runBlocking {
        val result = withSourceTimeout(5_000, onFailure = { it.message }) {
            throw IllegalStateException("解析不到")
        }
        assertThat(result).isEqualTo("解析不到")
    }

    @Test
    fun `外部取消要往外傳，協程不能繼續往下跑`() = runBlocking {
        // 關鍵在 reachedAfterCall：取消若被吞掉，withSourceTimeout 會正常回傳
        // onFailure 的值，協程就會繼續執行下一行——那正是 C-9 描述的「使用者
        // 離開畫面後那批抓取仍然跑完」。真的 rethrow 的話這一行永遠到不了。
        var reachedAfterCall = false
        val job = launch {
            withSourceTimeout(10_000, onFailure = { "failed" }) {
                delay(5_000)
                "never"
            }
            reachedAfterCall = true
        }
        delay(100)
        job.cancel()
        job.join()

        assertThat(reachedAfterCall).isFalse()
        assertThat(job.isCancelled).isTrue()
    }

    @Test
    fun `沒出事就回傳原值`() = runBlocking {
        val result = withSourceTimeout(5_000, onFailure = { "failed" }) { "ok" }
        assertThat(result).isEqualTo("ok")
    }
}
