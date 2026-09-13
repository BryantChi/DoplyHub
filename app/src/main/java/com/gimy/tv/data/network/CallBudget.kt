package com.gimy.tv.data.network

import okhttp3.Call
import java.util.concurrent.TimeUnit

/**
 * 把逾時預算真的套到這個 call 上。
 *
 * 專案裡所有抓取都是阻塞的 `execute()`，而 coroutine 的 `withTimeout` 只在掛起點生效，
 * 中斷不了阻塞呼叫——上層宣告「探測 3 秒」「取流 8 秒」若不套在 call 上，實際吃的是
 * 主 client 的 20 秒 callTimeout。只有 OkHttp 自己的計時器攔得住。
 *
 * [ms] 為 null 時不動，照用 client 的設定。
 */
internal fun Call.withBudget(ms: Long?): Call = apply {
    if (ms != null) timeout().timeout(ms, TimeUnit.MILLISECONDS)
}
