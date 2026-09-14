package com.gimy.tv.ui.detail

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 集數按鈕的文字。
 *
 * 會有這組測試是因為實機上（航海王 1178 集）整個集數區顯示成
 * 「第1141…」「第1142…」，完全看不出是第幾集——原本的規則是
 * 「超過 5 個字就截到 5 個字加刪節號」，而「第1141集」剛好 6 個字。
 * 截完變 7 個字，比原文還長，純粹是反效果。
 */
class EpisodeButtonLabelTest {

    @Test
    fun `四位數集數完整顯示，不截斷`() {
        assertThat(episodeButtonLabel("第1141集", 1141)).isEqualTo("第1141集")
        assertThat(episodeButtonLabel("第1178集", 1178)).isEqualTo("第1178集")
    }

    @Test
    fun `各種位數的純集數標題都原樣保留`() {
        assertThat(episodeButtonLabel("第1集", 1)).isEqualTo("第1集")
        assertThat(episodeButtonLabel("第01集", 1)).isEqualTo("第01集")
        assertThat(episodeButtonLabel("第123集", 123)).isEqualTo("第123集")
        assertThat(episodeButtonLabel("1141", 1141)).isEqualTo("1141")
    }

    @Test
    fun `具名標題仍然截斷，否則按鈕會被撐開`() {
        // 截斷的原始用意：讓每顆按鈕等高，中文不要在邊界斷得難看
        assertThat(episodeButtonLabel("特別篇 - 大結局", 99)).isEqualTo("特別篇 -…")
        assertThat(episodeButtonLabel("聖光篇 第1集", 1)).isEqualTo("聖光篇 第…")
    }

    @Test
    fun `剛好五個字的具名標題不截斷`() {
        assertThat(episodeButtonLabel("大結局特別篇", 99)).isEqualTo("大結局特別…")
        assertThat(episodeButtonLabel("最終回特別", 99)).isEqualTo("最終回特別")
    }

    @Test
    fun `標題空白時退回集號`() {
        assertThat(episodeButtonLabel("", 7)).isEqualTo("7")
        assertThat(episodeButtonLabel("   ", 1141)).isEqualTo("1141")
    }
}
