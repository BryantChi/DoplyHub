package com.gimy.tv.data.repository

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException

/**
 * 「給這個來源 N 毫秒，失敗就當它沒回應」——並行抓取裡每個來源都是這個形狀。
 *
 * 會抽出來是因為 catch 的順序很容易寫錯，而寫錯的兩種方式各壞一邊：
 *
 * - 只寫 `catch (e: Exception)`：連使用者離開畫面發出的 [CancellationException] 也一起吞掉，
 *   那批八個站的抓取照樣跑完，跟首頁重載搶同一組並行額度。
 * - 先 rethrow [CancellationException]：[TimeoutCancellationException] 繼承自它，
 *   於是逾時也被 rethrow，一個來源慢就把整批 coroutineScope 拖垮。
 *
 * 所以順序固定是：先接逾時當失敗、再放行真正的取消、最後才是一般例外。
 */
internal suspend fun <T> withSourceTimeout(
    timeoutMs: Long,
    onFailure: (Exception) -> T,
    block: suspend () -> T,
): T = try {
    withTimeout(timeoutMs) { block() }
} catch (e: TimeoutCancellationException) {
    onFailure(e)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    onFailure(e)
}
