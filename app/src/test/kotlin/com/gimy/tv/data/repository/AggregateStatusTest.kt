package com.gimy.tv.data.repository

import com.gimy.tv.domain.model.Confidence
import com.gimy.tv.domain.model.EpisodeStatus
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AggregateStatusTest {

    private fun vod(sourceType: SourceType, siteStatus: EpisodeStatus): Vod = Vod(
        id = sourceType.ordinal.toLong(),
        sourceType = sourceType,
        title = "test",
        coverUrl = "",
        category = "",
        year = 0,
        status = "",
        siteStatus = siteStatus,
    )

    @Test fun `all sites InProgress — max wins, tagged CrossSiteMax`() {
        val meta = mapOf(
            SourceType.GIMYTV to vod(SourceType.GIMYTV, EpisodeStatus.InProgress(120, Confidence.SiteDeclared)),
            SourceType.GIMYMAX to vod(SourceType.GIMYMAX, EpisodeStatus.InProgress(140, Confidence.SiteDeclared)),
        )
        val out = computeAggregatedStatus(
            normalizedMaxEp = 145,
            siteMetadata = meta,
            primaryFallbackStatus = EpisodeStatus.InProgress(120, Confidence.SiteDeclared),
        )
        assertThat(out).isEqualTo(EpisodeStatus.InProgress(145, Confidence.CrossSiteMax))
    }

    @Test fun `any site Finished — Finished wins, total = post-normalize max`() {
        val meta = mapOf(
            SourceType.GIMYTV to vod(SourceType.GIMYTV, EpisodeStatus.InProgress(120, Confidence.SiteDeclared)),
            SourceType.GIMYMAX to vod(SourceType.GIMYMAX, EpisodeStatus.Finished(150)),
        )
        val out = computeAggregatedStatus(
            normalizedMaxEp = 150,
            siteMetadata = meta,
            primaryFallbackStatus = EpisodeStatus.InProgress(120, Confidence.SiteDeclared),
        )
        assertThat(out).isEqualTo(EpisodeStatus.Finished(150))
    }

    @Test fun `Finished with all episodes failing to parse — total falls back to declared`() {
        val meta = mapOf(
            SourceType.GIMYMAX to vod(SourceType.GIMYMAX, EpisodeStatus.Finished(150)),
        )
        val out = computeAggregatedStatus(
            normalizedMaxEp = 0,  // all episodes failed to parse
            siteMetadata = meta,
            primaryFallbackStatus = EpisodeStatus.Empty,
        )
        assertThat(out).isEqualTo(EpisodeStatus.Finished(150))
    }

    @Test fun `outlier inflation — site declares 200, normalize cuts to 100, badge stays at 100`() {
        // Inverse of the 斗羅 2 case — protects against secondary outliers inflating
        // a number that the grid can't actually play.
        val meta = mapOf(
            SourceType.GIMYTV to vod(SourceType.GIMYTV, EpisodeStatus.InProgress(100, Confidence.SiteDeclared)),
            SourceType.GIMYMAX to vod(SourceType.GIMYMAX, EpisodeStatus.InProgress(200, Confidence.SiteDeclared)),
        )
        val out = computeAggregatedStatus(
            normalizedMaxEp = 100,  // normalize pruned the secondary's 200-line as cross-season outlier
            siteMetadata = meta,
            primaryFallbackStatus = EpisodeStatus.InProgress(100, Confidence.SiteDeclared),
        )
        assertThat(out).isEqualTo(EpisodeStatus.InProgress(100, Confidence.CrossSiteMax))
    }

    @Test fun `斗羅 2 — site declares 152, normalize keeps 152, badge says 152`() {
        val meta = mapOf(
            SourceType.GIMYTV to vod(SourceType.GIMYTV, EpisodeStatus.InProgress(152, Confidence.SiteDeclared)),
        )
        val out = computeAggregatedStatus(
            normalizedMaxEp = 152,
            siteMetadata = meta,
            primaryFallbackStatus = EpisodeStatus.InProgress(152, Confidence.SiteDeclared),
        )
        assertThat(out).isEqualTo(EpisodeStatus.InProgress(152, Confidence.CrossSiteMax))
    }

    @Test fun `all sites Empty, fallback empty — Empty out`() {
        val meta = mapOf(
            SourceType.GIMYTV to vod(SourceType.GIMYTV, EpisodeStatus.Empty),
        )
        val out = computeAggregatedStatus(
            normalizedMaxEp = 0,
            siteMetadata = meta,
            primaryFallbackStatus = EpisodeStatus.Empty,
        )
        assertThat(out).isEqualTo(EpisodeStatus.Empty)
    }

    @Test fun `all sites Empty but normalizedMaxEp gt 1 — InProgress with parsed value`() {
        val meta = mapOf(
            SourceType.GIMYTV to vod(SourceType.GIMYTV, EpisodeStatus.Empty),
        )
        val out = computeAggregatedStatus(
            normalizedMaxEp = 12,
            siteMetadata = meta,
            primaryFallbackStatus = EpisodeStatus.Empty,
        )
        assertThat(out).isEqualTo(EpisodeStatus.InProgress(12, Confidence.CrossSiteMax))
    }
}
