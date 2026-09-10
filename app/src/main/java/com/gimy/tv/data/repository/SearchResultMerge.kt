package com.gimy.tv.data.repository

import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod

/**
 * 把 [secondary] 併進 [primary]，同一部片只留一張卡。
 *
 * 與早期版本的差別：重複的那一筆不再直接丟掉，而是把它的來源與 vodId 記進留下來那張卡的
 * [Vod.altSources]。原本的做法讓排在後面的來源幾乎永遠不會出現在來源篩選裡——實測搜尋
 * 「恐怖」時 Eyny（合併順序第 5）回的 10 筆全被前四個來源蓋掉，使用者完全看不到 Eyny。
 *
 * [keyOf] 決定「哪些算同一部片」，由呼叫端提供（正式路徑用片名 + 季別）。抽出成參數是為了
 * 讓合併行為本身可以獨立測試，不必連片名正規化一起搬進來。
 */
internal fun mergeSearchResults(
    primary: List<Vod>,
    secondary: List<Vod>,
    keyOf: (Vod) -> Any,
): List<Vod> {
    val result = primary.toMutableList()
    // key → 在 result 裡的位置。同一個 key 只記最先出現的那張卡。
    val indexByKey = HashMap<Any, Int>()
    result.forEachIndexed { index, vod -> indexByKey.putIfAbsent(keyOf(vod), index) }

    for (vod in secondary) {
        val index = indexByKey[keyOf(vod)]
        if (index == null) {
            indexByKey[keyOf(vod)] = result.size
            result.add(vod)
            continue
        }
        val existing = result[index]
        // 同來源不必記成「替代來源」；同一來源重複出現時也只記最先看到的 id。
        if (vod.sourceType == existing.sourceType || vod.sourceType in existing.altSources) continue
        result[index] = existing.copy(altSources = existing.altSources + (vod.sourceType to vod.id))
    }
    return result
}

/** 這張卡在搜尋結果裡可歸屬的所有來源，供來源篩選使用。 */
fun Vod.searchSources(): Set<SourceType> = setOf(sourceType) + altSources.keys

/** 用 [source] 篩選時，這張卡該開哪一個 vodId。 */
fun Vod.idFor(source: SourceType): Long = if (source == sourceType) id else altSources[source] ?: id
