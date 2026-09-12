package com.gimy.tv.domain.util

import com.gimy.tv.domain.model.Confidence
import com.gimy.tv.domain.model.EpisodeStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EpisodeStatusParserTest {

    // ── In-progress patterns ──

    @Test fun `gimytv 更新至第N集 should yield InProgress`() {
        val s = parseEpisodeStatus("更新至第33集")
        assertThat(s).isInstanceOf(EpisodeStatus.InProgress::class.java)
        assertThat((s as EpisodeStatus.InProgress).latest).isEqualTo(33)
        assertThat(s.confidence).isEqualTo(Confidence.SiteDeclared)
        assertThat(s.display).isEqualTo("更新至 33 集")
    }

    @Test fun `更新N集 (no 至, no 第) should yield InProgress`() {
        val s = parseEpisodeStatus("更新26集") as EpisodeStatus.InProgress
        assertThat(s.latest).isEqualTo(26)
    }

    @Test fun `更新到N集 should yield InProgress`() {
        val s = parseEpisodeStatus("更新到 12 集") as EpisodeStatus.InProgress
        assertThat(s.latest).isEqualTo(12)
    }

    @Test fun `movieffm 更新N (no 集 suffix) should yield InProgress`() {
        val s = parseEpisodeStatus("更新26") as EpisodeStatus.InProgress
        assertThat(s.latest).isEqualTo(26)
    }

    @Test fun `斗羅 2 three-digit episode count should yield InProgress(152)`() {
        val s = parseEpisodeStatus("更新至第152集") as EpisodeStatus.InProgress
        assertThat(s.latest).isEqualTo(152)
        assertThat(s.display).isEqualTo("更新至 152 集")
    }

    // ── Finished patterns ──

    @Test fun `N集全 should yield Finished`() {
        val s = parseEpisodeStatus("38集全") as EpisodeStatus.Finished
        assertThat(s.total).isEqualTo(38)
        assertThat(s.display).isEqualTo("完結 · 共 38 集")
    }

    @Test fun `全N集 should yield Finished`() {
        val s = parseEpisodeStatus("全 24 集") as EpisodeStatus.Finished
        assertThat(s.total).isEqualTo(24)
    }

    @Test fun `完結 N 集 should yield Finished`() {
        val s = parseEpisodeStatus("完結 30 集") as EpisodeStatus.Finished
        assertThat(s.total).isEqualTo(30)
    }

    @Test fun `已完結 N 集 should yield Finished`() {
        val s = parseEpisodeStatus("已完結 12 集") as EpisodeStatus.Finished
        assertThat(s.total).isEqualTo(12)
    }

    // ── Movie / passthrough ──

    @Test fun `HD should yield Movie`() {
        val s = parseEpisodeStatus("HD") as EpisodeStatus.Movie
        assertThat(s.tag).isEqualTo("HD")
    }

    @Test fun `中字 should yield Movie`() {
        val s = parseEpisodeStatus("中字") as EpisodeStatus.Movie
        assertThat(s.tag).isEqualTo("中字")
    }

    @Test fun `bare 完結 (no count) should yield Raw`() {
        val s = parseEpisodeStatus("完結")
        assertThat(s).isInstanceOf(EpisodeStatus.Raw::class.java)
        assertThat((s as EpisodeStatus.Raw).text).isEqualTo("完結")
    }

    @Test fun `預告 should yield Raw`() {
        val s = parseEpisodeStatus("預告") as EpisodeStatus.Raw
        assertThat(s.text).isEqualTo("預告")
    }

    @Test fun `全集 (no count) should yield Raw`() {
        val s = parseEpisodeStatus("全集") as EpisodeStatus.Raw
        assertThat(s.text).isEqualTo("全集")
    }

    // ── Bare counts ──

    @Test fun `bare 第N集 should yield Raw (no progress claim)`() {
        // Legacy prettifyVodStatus passed bare counts verbatim. We preserve that:
        // a scraper emitting "第5集" as status is more likely a parsing mishap than
        // a real release-stage statement, so don't promote to InProgress.
        val s = parseEpisodeStatus("第5集") as EpisodeStatus.Raw
        assertThat(s.text).isEqualTo("第5集")
    }

    @Test fun `bare N集 should yield Raw (no progress claim)`() {
        val s = parseEpisodeStatus("12集") as EpisodeStatus.Raw
        assertThat(s.text).isEqualTo("12集")
    }

    // ── Empty / blank ──

    @Test fun `blank string should yield Empty`() {
        assertThat(parseEpisodeStatus("")).isEqualTo(EpisodeStatus.Empty)
        assertThat(parseEpisodeStatus("   ")).isEqualTo(EpisodeStatus.Empty)
    }

    // ── Edge: do not mangle compound labels ──

    @Test fun `OAD 5集 partial match should not mis-classify`() {
        val s = parseEpisodeStatus("OAD 5集")
        assertThat(s).isInstanceOf(EpisodeStatus.Raw::class.java)
    }

    @Test fun `預告 第3集 should keep verbatim`() {
        val s = parseEpisodeStatus("預告 第3集")
        assertThat(s).isInstanceOf(EpisodeStatus.Raw::class.java)
    }

    // ── 髒資料不得炸掉整批 ──
    // 這個 parser 是所有來源共用的，而呼叫端多半把整批 catch 掉：一筆解析失敗
    // 等於整個分類從畫面上消失，且毫無跡象。實際事故見 v3.1.10 的修正。

    @Test fun `日期被誤當集數時不得拋例外`() {
        // 實際發生過：movieffm 綜藝分類出現 "202520250915"，toInt() 拋
        // NumberFormatException，整個分類九列變八列。
        val s = parseEpisodeStatus("更新至第202520250915集")
        assertThat(s).isInstanceOf(EpisodeStatus.Raw::class.java)
    }

    @Test fun `超出合理範圍的集數落回 Raw 而非硬湊數字`() {
        // 上界 9999：真實影集不會有上萬集，超過幾乎必然是年份或 id 被誤配。
        val s = parseEpisodeStatus("全20250915集")
        assertThat(s).isInstanceOf(EpisodeStatus.Raw::class.java)
    }

    @Test fun `邊界內的大集數仍正常解析`() {
        // 確認上界沒有誤殺長壽動畫這類真的集數很多的作品。
        val s = parseEpisodeStatus("更新至第1200集")
        assertThat(s).isInstanceOf(EpisodeStatus.InProgress::class.java)
        assertThat((s as EpisodeStatus.InProgress).latest).isEqualTo(1200)
    }

    @Test fun `full-width digits fall through to Raw (current behavior pinning)`() {
        // Document gap: \d in regex doesn't match full-width digits. If a scraper
        // ever emits "更新至第１２集" we'll classify as Raw rather than InProgress.
        // Add normalisation only when a real source is found emitting these.
        val s = parseEpisodeStatus("更新至第１２集")
        assertThat(s).isInstanceOf(EpisodeStatus.Raw::class.java)
    }
}
