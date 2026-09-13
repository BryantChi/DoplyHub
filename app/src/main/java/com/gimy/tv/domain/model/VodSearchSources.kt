package com.gimy.tv.domain.model

/**
 * 搜尋結果合併後，一張卡可能同時代表好幾個來源的同一部片（altSources）。
 *
 * 這兩個函式只讀 [Vod] 自己的欄位，沒有任何資料層的依賴，卻一直放在
 * data.repository 底下，逼得搜尋畫面得 import 資料層才畫得出來源篩選。
 */

/** 這張卡在搜尋結果裡可歸屬的所有來源，供來源篩選使用。 */
fun Vod.searchSources(): Set<SourceType> = setOf(sourceType) + altSources.keys

/** 用 [source] 篩選時，這張卡該開哪一個 vodId。 */
fun Vod.idFor(source: SourceType): Long = if (source == sourceType) id else altSources[source] ?: id
