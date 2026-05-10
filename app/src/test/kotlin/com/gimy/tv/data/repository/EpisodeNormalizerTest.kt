package com.gimy.tv.data.repository

import com.gimy.tv.domain.model.Confidence
import com.gimy.tv.domain.model.Episode
import com.gimy.tv.domain.model.EpisodeGroup
import com.gimy.tv.domain.model.EpisodeStatus
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.domain.model.VodDetail
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EpisodeNormalizerTest {

    private fun line(name: String, count: Int, sourceType: SourceType? = null): EpisodeGroup =
        EpisodeGroup(
            sourceName = name,
            sourceId = name.hashCode(),
            episodes = (1..count).map { Episode(it, "第${it}集", "url/$it") },
            sourceType = sourceType,
        )

    private fun detail(
        siteStatus: EpisodeStatus,
        lines: List<EpisodeGroup>,
        primarySourceType: SourceType = SourceType.GIMYTV,
    ): VodDetail {
        val vod = Vod(
            id = 1L, sourceType = primarySourceType,
            title = "test", coverUrl = "", category = "", year = 0,
            status = "", siteStatus = siteStatus,
        )
        return VodDetail(vod, "", emptyList(), "", lines)
    }

    // ── R2 regression: 斗羅 2 ──

    @Test fun `site declares 152 with mixed lines 5-30-80-107-152 keeps the 152 line`() {
        val d = detail(
            siteStatus = EpisodeStatus.InProgress(152, Confidence.SiteDeclared),
            lines = listOf(
                line("壞線路 A", 5),
                line("壞線路 B", 30),
                line("一般線路", 80),
                line("一般線路 2", 107),
                line("最新線路", 152),
            ),
        )
        val out = EpisodeNormalizer.normalize(d)
        val maxEp = out.episodes.maxOf { it.episodes.maxOf { ep -> ep.number } }
        assertThat(maxEp).isEqualTo(152)
        assertThat(out.episodes.any { it.episodes.size == 152 }).isTrue()
    }

    @Test fun `site declares 100 — line of 250 still gets pruned (cross-season merge)`() {
        // Cross-season-merged outlier protection MUST still work even with site-declared baseline.
        val d = detail(
            siteStatus = EpisodeStatus.InProgress(100, Confidence.SiteDeclared),
            lines = listOf(
                line("正常 A", 100),
                line("正常 B", 95),
                line("正常 C", 100),
                line("跨季合併 outlier", 250),
            ),
        )
        val out = EpisodeNormalizer.normalize(d)
        val maxEp = out.episodes.maxOf { it.episodes.maxOf { ep -> ep.number } }
        assertThat(maxEp).isAtMost(160)  // 250 must be pruned (>60% above declared 100)
    }

    @Test fun `no site status — fall back to median (legacy behavior)`() {
        val d = detail(
            siteStatus = EpisodeStatus.Empty,
            lines = listOf(
                line("正常 A", 30),
                line("正常 B", 28),
                line("正常 C", 32),
                line("壞掉", 1),
            ),
        )
        val out = EpisodeNormalizer.normalize(d)
        // median(30,28,32,1) = 29; 壞掉=1 deviation = (29-1)/29 = 0.97 > 0.6 → dropped.
        assertThat(out.episodes.any { it.episodes.size == 1 }).isFalse()
    }

    @Test fun `episode-level prune — drop number-2026 misread`() {
        val brokenLine = EpisodeGroup(
            sourceName = "broken",
            sourceId = 1,
            episodes = listOf(
                Episode(1, "第1集", "u1"),
                Episode(2, "第2集", "u2"),
                Episode(2026, "預告 2026", "u3"),  // year mis-read as episode number
            ),
        )
        // Need 3+ lines for the line-level pass to engage; pad with two clean lines.
        val d = detail(
            siteStatus = EpisodeStatus.InProgress(2, Confidence.SiteDeclared),
            lines = listOf(brokenLine, line("正常 A", 2), line("正常 B", 2)),
        )
        val out = EpisodeNormalizer.normalize(d)
        val brokenAfter = out.episodes.firstOrNull { it.sourceName == "broken" }
        // Episode 2026 must be removed by Pass 1 regardless of whether the line survives Pass 2.
        if (brokenAfter != null) {
            assertThat(brokenAfter.episodes.map { it.number }).doesNotContain(2026)
        }
    }
}
