package com.gimy.tv.data.repository

import com.gimy.tv.domain.model.EpisodeGroup
import com.gimy.tv.domain.model.EpisodeStatus
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.VodDetail
import kotlin.math.abs

/**
 * Centralised cleanup for VodDetail.episodes.
 *
 * Lives in the repository layer so every call site (DetailViewModel,
 * PlayerViewModel, history-driven continue play) sees the same cleaned data —
 * the previous setup with per-screen [com.gimy.tv.ui.detail.DetailViewModel]
 * cleanup let buggy episodes leak into PlayerViewModel.playWithFallback.
 *
 * Two passes:
 *   1. Episode-level — drop episodes whose `number` falls outside the line's
 *      sane range (`1..size + GAP_TOLERANCE`). These come from parsers that
 *      mis-read year/date strings as the episode index ("預告 2026" → number=2026).
 *   2. Line-level — compute a baseline episode count, then drop lines whose count
 *      deviates >LINE_DEVIATION_THRESHOLD from that baseline. Catches
 *      cross-season-merged lines (S1+S2 stitched into one 100-ep listing while the
 *      show is actually 30 eps) and lines whose parser completely failed (size 1
 *      when others have 25).
 *
 * Baseline selection ([pickBaseline]):
 *   - If the site declares an in-progress episode count (siteStatus=InProgress),
 *     that value is used as the authoritative baseline. This prevents median from
 *     incorrectly dropping the newest, highest-count line (the 斗羅 2 regression).
 *   - If the site declares a finished total (siteStatus=Finished), that is used.
 *   - Otherwise, fall back to the cluster median of primary-source lines (legacy
 *     v2.7.0 behaviour, triggered when siteStatus=Empty or Raw).
 *
 * Also computes [EpisodeGroup.confidence] = `1 - |size - baseline| / baseline`,
 * clamped to `[0, 1]`. UI uses this to mark suspect lines without dropping them
 * outright.
 */
object EpisodeNormalizer {

    /** Allow this many gaps in episode numbering before treating high numbers
     *  as parser misreads. Keeps "1, 2, 3, 5, 6" working when episode 4 is just
     *  missing from the source. */
    private const val GAP_TOLERANCE = 5

    /** Lines whose count is more than this fraction off from the cluster baseline
     *  are dropped. 0.6 = ±60%; tolerates stale (-30%) and small overshoots
     *  while excluding cross-season-merged outliers. */
    private const val LINE_DEVIATION_THRESHOLD = 0.6

    fun normalize(detail: VodDetail): VodDetail {
        val primarySourceType = detail.vod.sourceType
        // Pass 1: per-line episode cleanup.
        val cleanedEpisodes = detail.episodes.map { line ->
            val size = line.episodes.size
            val cap = size + GAP_TOLERANCE
            val keptEpisodes = line.episodes.filter { it.number in 1..cap }
            if (keptEpisodes.size == line.episodes.size) line
            else line.copy(episodes = keptEpisodes)
        }

        // Skip line-level filtering when there's not enough data:
        //   - movies (single episode) — nothing to compare
        //   - one or two lines — clustering is meaningless, would over-prune
        if (cleanedEpisodes.size < 3 || cleanedEpisodes.all { it.episodes.size <= 1 }) {
            return detail.copy(episodes = cleanedEpisodes.filter { it.episodes.isNotEmpty() })
        }

        val baseline = pickBaseline(detail.vod.siteStatus, cleanedEpisodes, primarySourceType)
        if (baseline <= 0) {
            return detail.copy(episodes = cleanedEpisodes.filter { it.episodes.isNotEmpty() })
        }

        // Pass 2: drop outlier lines + tag confidence on survivors.
        val survivors = cleanedEpisodes.mapNotNull { line ->
            val size = line.episodes.size
            if (size == 0) return@mapNotNull null
            val deviation = abs(size - baseline).toDouble() / baseline
            if (deviation > LINE_DEVIATION_THRESHOLD) {
                // Outlier: cross-season merge (size >> baseline) or broken parser
                // (size << baseline). Drop entirely so it can't pollute count
                // calculation or appear as a misleading playback option.
                null
            } else {
                val confidence = (1.0 - deviation).coerceIn(0.0, 1.0).toFloat()
                line.copy(confidence = confidence)
            }
        }

        // Belt-and-braces: if everything got dropped, fall back to cleaned-but-
        // unfiltered list so the user still sees something.
        val finalEpisodes = if (survivors.isEmpty()) {
            cleanedEpisodes.filter { it.episodes.isNotEmpty() }
        } else {
            survivors
        }
        return detail.copy(episodes = finalEpisodes)
    }

    /**
     * Determines the authoritative episode-count baseline for outlier detection.
     *
     * Priority:
     * 1. Site-declared in-progress latest episode count (highest authority — the
     *    site's own numbering prevents median from killing the newest line).
     * 2. Site-declared finished total (equally authoritative for completed shows).
     * 3. Median of primary-source lines (legacy fallback when site status is absent).
     */
    internal fun pickBaseline(
        siteStatus: EpisodeStatus,
        cleanedEpisodes: List<EpisodeGroup>,
        primarySourceType: SourceType,
    ): Int {
        // The `> 1` guards reject baseline = 1 cases. A first-week show declares
        // "更新至第 1 集" → siteStatus.latest = 1; using that as the prune
        // baseline would drop any line that managed to parse 2 episodes (a stale
        // parser quirk we want kept, not discarded). Falls through to median,
        // and if median is also 1 the all-singletons short-circuit upstream
        // already returned without pruning.
        when (siteStatus) {
            is EpisodeStatus.InProgress -> if (siteStatus.latest > 1) return siteStatus.latest
            is EpisodeStatus.Finished -> if (siteStatus.total > 1) return siteStatus.total
            else -> {}
        }
        val primaryLines = cleanedEpisodes.filter {
            it.sourceType == null || it.sourceType == primarySourceType
        }
        val baseLines = if (primaryLines.size >= 2) primaryLines else cleanedEpisodes
        return computeMedian(baseLines.map { it.episodes.size }.filter { it > 0 })
    }

    private fun computeMedian(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
