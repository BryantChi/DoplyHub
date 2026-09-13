package com.gimy.tv.domain.model

/**
 * Cross-source standard category. UI layer uses this enum to address categories;
 * each SiteSource maps it to its own typeId via [SiteCategoryMap].
 *
 * Why an enum: 5 new MacCMS sites use completely different typeId numbering
 * (e.g. 23 = 韓劇 in gimy.tw but = 倫理片 in 123kubo). Hardcoding numeric typeIds
 * across the app is unsafe.
 */
enum class StandardCategory {
    MOVIE, SERIES, ANIME, VARIETY,
    KOREAN, CHINESE, HK, TAIWAN, JAPANESE, AMERICAN,
    DOCUMENTARY, ADULT
}

/** One adult typeId on a source, with the user-facing label that distinguishes it.
 *  Most sites have a single entry; gimy.tw has two (typeId=39 真·露骨 and typeId=27 劇情倫理). */
data class AdultEntry(val typeId: Int, val label: String)

/**
 * Per-source typeId table. `adultCategories` carries 0..N adult-zone IDs with labels
 * (multiple supported because gimy.tw exposes both露骨 and 劇情倫理 under different typeIds).
 * Filtering uses each source's own list — never a single hard-coded constant.
 */
data class SiteCategoryMap(
    val movie: Int,
    val series: Int,
    val variety: Int,
    val anime: Int,
    val korean: Int,
    val chinese: Int,
    val hk: Int,
    val taiwan: Int,
    val japanese: Int,
    val american: Int,
    val documentary: Int,
    val adultCategories: List<AdultEntry> = emptyList(),
) {
    /** First adult typeId, or -1 if the source has none. Kept for backward compat with
     *  callers that just need a "does this source have any adult content?" check. */
    val adult: Int get() = adultCategories.firstOrNull()?.typeId ?: -1

    fun typeIdFor(category: StandardCategory): Int = when (category) {
        StandardCategory.MOVIE -> movie
        StandardCategory.SERIES -> series
        StandardCategory.VARIETY -> variety
        StandardCategory.ANIME -> anime
        StandardCategory.KOREAN -> korean
        StandardCategory.CHINESE -> chinese
        StandardCategory.HK -> hk
        StandardCategory.TAIWAN -> taiwan
        StandardCategory.JAPANESE -> japanese
        StandardCategory.AMERICAN -> american
        StandardCategory.DOCUMENTARY -> documentary
        StandardCategory.ADULT -> adult
    }

    fun categoryFor(typeId: Int): StandardCategory? = when (typeId) {
        movie -> StandardCategory.MOVIE
        series -> StandardCategory.SERIES
        variety -> StandardCategory.VARIETY
        anime -> StandardCategory.ANIME
        korean -> StandardCategory.KOREAN
        chinese -> StandardCategory.CHINESE
        hk -> StandardCategory.HK
        taiwan -> StandardCategory.TAIWAN
        japanese -> StandardCategory.JAPANESE
        american -> StandardCategory.AMERICAN
        documentary -> StandardCategory.DOCUMENTARY
        adult -> if (adult > 0) StandardCategory.ADULT else null
        else -> null
    }
}

/**
 * 分類的顯示名稱。
 *
 * 名稱只綁 [StandardCategory]，不綁 typeId——同一個 typeId 在不同站台是不同分類
 * （gimy 的 14 是台劇、gimy.tw 的 14 是港劇），用 typeId 當 key 的對照表在多來源下
 * 必然會錯。2026-09-13 的「點日劇顯示港劇」就是這樣來的。
 */
val StandardCategory.displayName: String get() = when (this) {
    StandardCategory.MOVIE -> "電影"
    StandardCategory.SERIES -> "電視劇"   // 站台頁面標題就是「電視劇」；HomeScreen 原本寫「劇集」
    StandardCategory.ANIME -> "動漫"
    StandardCategory.VARIETY -> "綜藝"
    StandardCategory.KOREAN -> "韓劇"
    StandardCategory.CHINESE -> "陸劇"
    StandardCategory.HK -> "港劇"
    StandardCategory.TAIWAN -> "台劇"
    StandardCategory.JAPANESE -> "日劇"
    StandardCategory.AMERICAN -> "美劇"
    StandardCategory.DOCUMENTARY -> "紀錄片"
    StandardCategory.ADULT -> "倫理"
}

val SourceType.categoryMap: SiteCategoryMap get() = when (this) {
    // Existing sources — preserve current numbering
    // gimytv.ai / gimy01.tv: hk=21 (not 15), japanese=15 (not 21), documentary=22 (not 3 / 30).
    // Verified 2026-05-08 by hitting /type/{id}.html and reading the page <title>.
    SourceType.GIMYTV -> SiteCategoryMap(
        movie = 1, series = 2, variety = 29, anime = 4,
        korean = 20, chinese = 13, hk = 21, taiwan = 14,
        japanese = 15, american = 16, documentary = 22,
    )
    SourceType.GIMYMAX -> SiteCategoryMap(
        movie = 1, series = 2, variety = 29, anime = 4,
        korean = 20, chinese = 13, hk = 21, taiwan = 14,
        japanese = 15, american = 16, documentary = 22,
    )
    // movieffm 的編號自成一套（路徑＋query，見 MovieffmSource.categoryRoutes）。
    // movie 刻意指向 101「熱門電影」而不是 100「電影」：首頁那一列要的是熱門榜。
    // japanese/series 原本寫 -1，但首頁其實有在抓 204 與 200——那是漏填，不是真的沒有。
    SourceType.MOVIEFFM -> SiteCategoryMap(
        movie = 101, series = 200, variety = 206, anime = 205,
        korean = 201, chinese = 202, hk = 208, taiwan = 207,
        japanese = 204, american = 203, documentary = -1,
    )

    // New sources (5)
    SourceType.GIMY_TW -> SiteCategoryMap(
        movie = 1, series = 2, variety = 3, anime = 4,
        korean = 23, chinese = 13, hk = 14, taiwan = 15,
        japanese = 16, american = 24, documentary = 20,
        // Two distinct adult zones: 39 = explicit, 27 = soft-core / R-rated drama
        adultCategories = listOf(
            AdultEntry(39, "露骨"),
            AdultEntry(27, "劇情倫理"),
        ),
    )
    SourceType.EYNY_TV -> SiteCategoryMap(
        movie = 1, series = 2, variety = 3, anime = 4,
        korean = 23, chinese = 13, hk = 14, taiwan = 15,
        japanese = 16, american = 24, documentary = 20,
        adultCategories = listOf(AdultEntry(27, "倫理")),
    )
    SourceType.IMAPLE_TV -> SiteCategoryMap(
        movie = 1, series = 2, variety = 3, anime = 4,
        korean = 15, chinese = 13, hk = 21, taiwan = 20,
        japanese = 22, american = 16, documentary = 32,
        adultCategories = listOf(AdultEntry(59, "倫理")),
    )
    SourceType.MOMOVOD -> SiteCategoryMap(
        movie = 1, series = 2, variety = 3, anime = 4,
        korean = 23, chinese = 13, hk = 14, taiwan = 15,
        japanese = 16, american = 24, documentary = 20,
        adultCategories = listOf(AdultEntry(27, "倫理")),
    )
    SourceType.KUBO123 -> SiteCategoryMap(
        movie = 1, series = 2, variety = 3, anime = 4,
        korean = 24, chinese = 13, hk = 14, taiwan = 15,
        japanese = 16, american = 25, documentary = 20,
        adultCategories = listOf(AdultEntry(23, "倫理")),
    )

    // Adult-plus sources — no standard category model (path-based listings instead).
    // AdultPlusScreen wires these directly with hardcoded list URLs per row, bypassing
    // the typeId mechanism. categoryMap is a placeholder to satisfy the exhaustive when.
    SourceType.JABLE_TV -> SiteCategoryMap(
        movie = -1, series = -1, variety = -1, anime = -1,
        korean = -1, chinese = -1, hk = -1, taiwan = -1,
        japanese = -1, american = -1, documentary = -1,
    )
    SourceType.XNXX -> SiteCategoryMap(
        movie = -1, series = -1, variety = -1, anime = -1,
        korean = -1, chinese = -1, hk = -1, taiwan = -1,
        japanese = -1, american = -1, documentary = -1,
    )
    SourceType.FORUM5278 -> SiteCategoryMap(
        movie = -1, series = -1, variety = -1, anime = -1,
        korean = -1, chinese = -1, hk = -1, taiwan = -1,
        japanese = -1, american = -1, documentary = -1,
    )
}

/**
 * 依指定順序展開成「typeId → 顯示名稱」，這個來源沒有的分類自動跳過。
 *
 * 首頁那幾列原本是手寫的 `20 to "韓劇", 13 to "陸劇", …`，等於把編號表又抄了一份；
 * 抄本與 [categoryMap] 一旦對不上（實際發生過：movieffm 的 204 日劇在 map 裡被寫成 -1），
 * 沒有任何編譯錯誤會攔下來。
 *
 * [labelOverrides] 留給「站台自己的命名與標準名稱不同」的例外，
 * 例如 movieffm 的 101 是熱門電影榜而不是一般電影分類。
 */
fun SourceType.categoryRows(
    order: List<StandardCategory>,
    labelOverrides: Map<StandardCategory, String> = emptyMap(),
): List<Pair<Int, String>> = order.mapNotNull { cat ->
    categoryMap.typeIdFor(cat).takeIf { it > 0 }
        ?.let { typeId -> typeId to (labelOverrides[cat] ?: cat.displayName) }
}
