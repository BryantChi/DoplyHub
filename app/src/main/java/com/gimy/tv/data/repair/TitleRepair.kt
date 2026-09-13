package com.gimy.tv.data.repair

/** What the scrapers write when they cannot determine a title. */
private const val PARSER_FALLBACK_TITLE = "Unknown"

/**
 * Guards the one-off title repair, which writes into the user's own favourites and history.
 *
 * A wrong-but-readable title is still better than "Unknown" or a blank, so anything short of
 * a real, different title leaves the existing row alone.
 */
internal fun shouldReplaceTitle(old: String, fetched: String?): Boolean {
    val clean = fetched?.trim().orEmpty()
    if (clean.isBlank()) return false
    if (clean == PARSER_FALLBACK_TITLE) return false
    return clean != old.trim()
}

/**
 * 修復最多嘗試幾次冷啟動。
 *
 * 抓失敗不標記完成，是為了讓下一次啟動把沒做完的補上——jable 的 Cloudflare 挑戰常常
 * 下一輪就過了。問題是「有失敗就重跑」沒有上限：站台整個連不上、或某些 row 的 slug
 * 根本還沒被記錄過（slug 只有使用者手動開過該片才會留下），就會變成每次冷啟動都把
 * 整批重跑一遍，每筆間隔 1.2 秒，跟首頁載入搶同一組連線額度。
 */
internal const val MAX_REPAIR_ATTEMPTS = 5

/**
 * 這次冷啟動要不要再跑一次修復。
 *
 * 暫時性失敗仍有好幾次機會，永久性失敗則會停下來——修好的是使用者看得到的標題，
 * 少修幾筆遠比每次開 App 都拖慢首頁划算。
 */
internal fun shouldAttemptRepair(done: Boolean, attempts: Int): Boolean =
    !done && attempts < MAX_REPAIR_ATTEMPTS
