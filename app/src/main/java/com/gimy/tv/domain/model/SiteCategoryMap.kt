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
    SourceType.MOVIEFFM -> SiteCategoryMap(
        // Movieffm uses different typeId range; -1 marks unsupported
        movie = 101, series = -1, variety = 206, anime = 205,
        korean = 201, chinese = 202, hk = 208, taiwan = 207,
        japanese = -1, american = 203, documentary = -1,
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
}
