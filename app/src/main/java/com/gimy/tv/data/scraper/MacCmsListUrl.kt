package com.gimy.tv.data.scraper

/**
 * MacCms 站台的列表分頁網址格式。
 *
 * [STATIC] 是 `{listUrlPath}/{id}.html`、`{listUrlPath}/{id}-{page}.html`。
 * [VODSHOW] 是 `/vodshow/{id}--------{page}---.html`：gimy.tw 2026-10 起靜態路徑不再分頁
 * （第 2 頁回的仍是第 1 頁），只有這個格式會真的翻頁。
 */
enum class MacCmsListStyle { STATIC, VODSHOW }

/** 組出 MacCms 列表頁網址。 */
internal fun macCmsListUrl(
    baseUrl: String,
    listUrlPath: String,
    typeId: Int,
    page: Int,
    style: MacCmsListStyle,
): String = when (style) {
    MacCmsListStyle.STATIC ->
        if (page <= 1) "$baseUrl$listUrlPath/$typeId.html"
        else "$baseUrl$listUrlPath/$typeId-$page.html"
    // 第 1 頁也走 vodshow：靜態路徑的第 1 頁是各子分類的總覽，跟 vodshow 第 2 頁接不起來。
    MacCmsListStyle.VODSHOW -> "$baseUrl/vodshow/$typeId--------${page.coerceAtLeast(1)}---.html"
}
