package com.gimy.tv.domain.model

/**
 * Single source of truth for "how many episodes / what release stage" badges across
 * the app. Replaces the v2.x trio of (a) raw `Vod.status: String`, (b) episode list
 * max() compute scattered across UI, (c) per-screen 4-regex finished detection.
 *
 * Construction is centralised in [com.gimy.tv.domain.util.parseEpisodeStatus].
 * UI MUST read [display] only — branching on the variant for display logic
 * re-introduces the v2.x problem this redesign fixes.
 */
sealed interface EpisodeStatus {
    val display: String

    /** Series in progress — `latest` is the highest known episode number. */
    data class InProgress(
        val latest: Int,
        val confidence: Confidence,
    ) : EpisodeStatus {
        override val display: String = "更新至 $latest 集"
    }

    /** Series finished — `total` is the final episode count. */
    data class Finished(val total: Int) : EpisodeStatus {
        override val display: String = "完結 · 共 $total 集"
    }

    /** Movie (no episode count). `tag` carries quality / language label like "HD" / "中字". */
    data class Movie(val tag: String) : EpisodeStatus {
        override val display: String = tag
    }

    /** Status string was non-empty but couldn't be parsed into a count or movie tag.
     *  Pass-through (e.g. "預告" / "全集"). UI shows verbatim. */
    data class Raw(val text: String) : EpisodeStatus {
        override val display: String = text
    }

    /** Status string was blank / null. UI shows nothing. */
    data object Empty : EpisodeStatus {
        override val display: String = ""
    }
}

/** Provenance of [EpisodeStatus.InProgress.latest]. Phase 2 enrichment can promote
 *  a [SiteDeclared] / [ParsedFromLines] status to [CrossSiteMax] without making the
 *  badge number jump (UI keys animations on the value, not the confidence). */
enum class Confidence {
    /** Number came verbatim from the site's listing string ("更新至第 N 集"). */
    SiteDeclared,
    /** Number came from `max(episode.number)` over this site's parsed play lines. */
    ParsedFromLines,
    /** Number came from `max(episode.number)` aggregated across all enriched sites. */
    CrossSiteMax,
}
