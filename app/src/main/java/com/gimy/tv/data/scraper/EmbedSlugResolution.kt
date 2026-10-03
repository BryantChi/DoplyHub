package com.gimy.tv.data.scraper

/**
 * Resolves a slug-backed vodId, falling back to persistent storage when the in-memory
 * reverse map has been lost.
 *
 * Jable/XNXX detail URLs are slugs while Vod.id must be a Long, so the id is a one-way hash
 * — nothing can reconstruct the slug from the id alone. Favourites and history store only
 * the id, so once the process dies the mapping is gone and every saved entry fails. Keeping
 * this as a plain function (rather than inlining it in the scraper) keeps the branch that
 * actually breaks unit-testable, since the scrapers need OkHttp and Room to construct.
 *
 * A storage failure degrades to null so the caller raises its usual actionable error
 * instead of crashing the detail screen.
 */
internal suspend fun resolveSlug(
    vodId: Long,
    fromMemory: (Long) -> String?,
    fromDb: suspend (Long) -> String?,
    remember: (Long, String) -> Unit,
): String? {
    fromMemory(vodId)?.let { return it }
    val stored = runCatching { fromDb(vodId) }.getOrNull() ?: return null
    remember(vodId, stored)
    return stored
}

/**
 * slug（或 cuid）→ 穩定的非負 Long id。
 *
 * 演算法一個字都不能改：Jable／XNXX／Imaple 的收藏與觀看紀錄只存這個 id，改了就全部對不回 slug。
 */
internal fun slugStableId(slug: String): Long {
    var h = 1125899906842597L
    for (c in slug) h = 31L * h + c.code.toLong()
    return h and 0x7FFFFFFFFFFFFFFFL  // 去掉符號位，Vod.id 才不會是負數
}
