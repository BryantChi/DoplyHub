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

/**
 * Per-source typeId table. `adult = -1` means the source has no adult category
 * (or it is an off-site link). Filtering must use each source's own `adult` value.
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
    val adult: Int = -1,
) {
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
        adult = 39,
    )
    SourceType.EYNY_TV -> SiteCategoryMap(
        movie = 1, series = 2, variety = 3, anime = 4,
        korean = 23, chinese = 13, hk = 14, taiwan = 15,
        japanese = 16, american = 24, documentary = 20,
        adult = 27,
    )
    SourceType.IMAPLE_TV -> SiteCategoryMap(
        movie = 1, series = 2, variety = 3, anime = 4,
        korean = 15, chinese = 13, hk = 21, taiwan = 20,
        japanese = 22, american = 16, documentary = 32,
        adult = 59,
    )
    SourceType.MOMOVOD -> SiteCategoryMap(
        movie = 1, series = 2, variety = 3, anime = 4,
        korean = 23, chinese = 13, hk = 14, taiwan = 15,
        japanese = 16, american = 24, documentary = 20,
        adult = 27,
    )
    SourceType.KUBO123 -> SiteCategoryMap(
        movie = 1, series = 2, variety = 3, anime = 4,
        korean = 24, chinese = 13, hk = 14, taiwan = 15,
        japanese = 16, american = 25, documentary = 20,
        adult = 23,
    )
}
