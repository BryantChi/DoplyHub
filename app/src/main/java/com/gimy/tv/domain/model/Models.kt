package com.gimy.tv.domain.model

enum class SourceType {
    GIMYMAX, GIMYTV, MOVIEFFM,
    GIMY_TW, EYNY_TV, IMAPLE_TV, MOMOVOD, KUBO123,
    JABLE_TV, XNXX, FORUM5278  // adult-plus sources
}

val SourceType.displayName: String get() = when (this) {
    SourceType.GIMYMAX -> "GimyMax"
    SourceType.GIMYTV -> "GimyTV"
    SourceType.MOVIEFFM -> "MovieFFM"
    SourceType.GIMY_TW -> "Gimy"
    SourceType.EYNY_TV -> "Eyny"
    SourceType.IMAPLE_TV -> "Imaple"
    SourceType.MOMOVOD -> "Momo"
    SourceType.KUBO123 -> "Kubo"
    SourceType.JABLE_TV -> "Jable"
    SourceType.XNXX -> "XNXX"
    SourceType.FORUM5278 -> "5278"
}

data class Category(
    val id: Int,
    val name: String,
    val sourceType: SourceType
)

data class Vod(
    val id: Long,
    val sourceType: SourceType,
    val title: String,
    val coverUrl: String,
    val category: String,
    val year: Int,
    val status: String,
    val rating: Double? = null
)

data class VodDetail(
    val vod: Vod,
    val director: String,
    val actors: List<String>,
    val synopsis: String,
    val episodes: List<EpisodeGroup>,
    val seriesVods: List<Vod> = emptyList(),
    val relatedVods: List<Vod> = emptyList()
)

/**
 * One play line of a vod from one scraper. Naming clarification:
 *
 *   - **SourceType / SiteSource** = the website/scraper (GimyTv / Imaple / 5278 / …).
 *   - **EpisodeGroup** = one play line within a site (順暢 / 無盡 / 卧龍雲 / …).
 *
 * After cross-source enrichment, a single VodDetail.episodes list holds groups from
 * MULTIPLE sites — each one carrying its own `sourceType` so PlayerViewModel can route
 * playUrl resolution back to the right scraper.
 */
data class EpisodeGroup(
    /** Display name for the play line (e.g. "順暢", "[GimyMax] 無盡"). */
    val sourceName: String,
    /** Stable line id within a vod. Primary lines = positive (assigned by the scraper);
     *  secondary lines from cross-source enrichment = encoded as negative
     *  (-(sourceType.ordinal*100 + lineId + 1)) so they're unique across all sites. */
    val sourceId: Int,
    val episodes: List<Episode>,
    /** Actual scraper SourceType for cross-source enriched groups. When null, callers
     *  fall back to the VodDetail's primary sourceType (backward-compat for primary's
     *  own groups). */
    val sourceType: SourceType? = null,
    /** Optional per-line tier hint set by the scraper. Lower = more preferred. When
     *  null, ranking falls back to the substring-pattern heuristic in
     *  VodRepositoryImpl.rankEpisodeGroups. Scrapers that know which of their internal
     *  lines are stable should populate this so we don't depend on display-string
     *  matching that breaks if upstream renames a line. */
    val linePriority: Int? = null,
    /** Raw, unencoded line id within the originating scraper. For secondary groups
     *  produced by enrichment this is the natural positive id; for primary groups
     *  callers should fall back to [sourceId] (which already IS the natural id).
     *  The negatively-encoded [sourceId] for secondary groups remains stable across
     *  the merged list and is what watch-history persists, so we don't change it
     *  here; this field is purely additive — gives later code a clear handle to the
     *  un-encoded id without re-implementing the `-(ordinal*100 + lineId + 1)` math. */
    val lineId: Int? = null,
    /** Episode-count confidence relative to the cluster of lines for this vod.
     *  Set by EpisodeNormalizer in the repository layer. Range `[0, 1]`:
     *    1.0 = count matches cluster median exactly
     *    0.7~0.9 = within 30% of median (treated as reliable)
     *    < 0.7 = noticeably divergent (UI flags with ⚠ icon)
     *    < 0.4 = filtered out before this field surfaces (line gets dropped)
     *  Null means clustering wasn't possible (single-line vod, movie, etc.) and
     *  callers should treat the line as full-confidence. */
    val confidence: Float? = null,
)

data class Episode(
    val number: Int,
    val title: String,
    val playUrl: String,
    /** Episode kind marker — `null` for main-line episodes, otherwise short tag
     *  like "OAD" / "番外" / "特別篇" / "劇場版" / "外傳". Lets watch-history
     *  disambiguate "OAD 5" from "第 5 集" even when both share the same
     *  numeric `number`. Parsers populate this when they detect the marker in
     *  the original episode label. */
    val kind: String? = null,
)

data class PlayerData(
    val streamUrl: String,
    val encrypt: Int,
    val from: String
)

data class PaginatedResult<T>(
    val items: List<T>,
    val currentPage: Int,
    val totalPages: Int,
    val hasMore: Boolean
)
