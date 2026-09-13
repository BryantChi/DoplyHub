package com.gimy.tv.ui.player

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 釘住快捷選單的按鍵行為。
 *
 * 為什麼需要：這是整個播放器唯一有分支邏輯的按鍵處理，而播放器的按鍵在
 * 2026-09-13 的稽核中被發現整組是死碼（Compose 端沒有 requestFocus，事件根本
 * 進不了 PlayerView，快轉與 BACK 分支從來沒被呼叫過）。那種錯誤沒有測試就只能
 * 靠上機一顆一顆按才會發現。
 *
 * 這裡刻意不碰 Compose 與 Android View，只測「狀態 + 按鍵 → 新狀態 + 動作」。
 */
class QuickMenuKeyTest {

    private val ctx = QuickMenuContext(
        sourceCount = 3,
        episodeCount = 10,
        currentSourceIndex = 1,
        currentEpisodeIndex = 4,
    )

    private val closed = QuickMenuState()

    @Test
    fun `選單關閉時上鍵開啟選單並停在目前這集`() {
        val r = reduceMenuKey(closed, KeyEvent.KEYCODE_DPAD_UP, ctx)
        assertThat(r.state.open).isTrue()
        assertThat(r.state.row).isEqualTo(MenuRow.EPISODE)
        assertThat(r.state.index).isEqualTo(4)
        assertThat(r.action).isEqualTo(MenuAction.Consumed)
    }

    @Test
    fun `選單關閉時其他按鍵一律讓給播放器`() {
        for (key in listOf(
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_DPAD_DOWN,
        )) {
            val r = reduceMenuKey(closed, key, ctx)
            assertThat(r.action).isEqualTo(MenuAction.PassThrough)
            assertThat(r.state).isEqualTo(closed)
        }
    }

    @Test
    fun `選單開啟時上下鍵在兩列之間切換並各自定位到目前項目`() {
        val open = QuickMenuState(open = true, row = MenuRow.EPISODE, index = 4)

        val up = reduceMenuKey(open, KeyEvent.KEYCODE_DPAD_UP, ctx)
        assertThat(up.state.row).isEqualTo(MenuRow.SOURCE)
        assertThat(up.state.index).isEqualTo(1)

        val down = reduceMenuKey(up.state, KeyEvent.KEYCODE_DPAD_DOWN, ctx)
        assertThat(down.state.row).isEqualTo(MenuRow.EPISODE)
        assertThat(down.state.index).isEqualTo(4)
    }

    @Test
    fun `選單開啟時左右鍵在列內移動且不會越界`() {
        val atStart = QuickMenuState(open = true, row = MenuRow.EPISODE, index = 0)
        assertThat(reduceMenuKey(atStart, KeyEvent.KEYCODE_DPAD_LEFT, ctx).state.index).isEqualTo(0)
        assertThat(reduceMenuKey(atStart, KeyEvent.KEYCODE_DPAD_RIGHT, ctx).state.index).isEqualTo(1)

        val atEnd = QuickMenuState(open = true, row = MenuRow.EPISODE, index = 9)
        assertThat(reduceMenuKey(atEnd, KeyEvent.KEYCODE_DPAD_RIGHT, ctx).state.index).isEqualTo(9)
        assertThat(reduceMenuKey(atEnd, KeyEvent.KEYCODE_DPAD_LEFT, ctx).state.index).isEqualTo(8)
    }

    @Test
    fun `線路列的右界是線路數而不是集數`() {
        val atEnd = QuickMenuState(open = true, row = MenuRow.SOURCE, index = 2)
        assertThat(reduceMenuKey(atEnd, KeyEvent.KEYCODE_DPAD_RIGHT, ctx).state.index).isEqualTo(2)
    }

    @Test
    fun `OK 鍵依所在列回報選定哪一項並關閉選單`() {
        val onEpisode = QuickMenuState(open = true, row = MenuRow.EPISODE, index = 7)
        val ep = reduceMenuKey(onEpisode, KeyEvent.KEYCODE_DPAD_CENTER, ctx)
        assertThat(ep.action).isEqualTo(MenuAction.PickEpisode(7))
        assertThat(ep.state.open).isFalse()

        val onSource = QuickMenuState(open = true, row = MenuRow.SOURCE, index = 2)
        val src = reduceMenuKey(onSource, KeyEvent.KEYCODE_DPAD_CENTER, ctx)
        assertThat(src.action).isEqualTo(MenuAction.PickSource(2))
        assertThat(src.state.open).isFalse()
    }

    @Test
    fun `選到目前正在播的那一項時不觸發切換`() {
        val onCurrentEp = QuickMenuState(open = true, row = MenuRow.EPISODE, index = 4)
        val r = reduceMenuKey(onCurrentEp, KeyEvent.KEYCODE_DPAD_CENTER, ctx)
        assertThat(r.action).isEqualTo(MenuAction.Consumed)
        assertThat(r.state.open).isFalse()
    }

    @Test
    fun `BACK 只關選單不離開播放器`() {
        val open = QuickMenuState(open = true, row = MenuRow.EPISODE, index = 4)
        val r = reduceMenuKey(open, KeyEvent.KEYCODE_BACK, ctx)
        assertThat(r.action).isEqualTo(MenuAction.Consumed)
        assertThat(r.state.open).isFalse()
    }

    @Test
    fun `沒有任何線路或集數時上鍵不開選單`() {
        val empty = QuickMenuContext(0, 0, 0, 0)
        val r = reduceMenuKey(closed, KeyEvent.KEYCODE_DPAD_UP, empty)
        assertThat(r.state.open).isFalse()
        assertThat(r.action).isEqualTo(MenuAction.PassThrough)
    }
}
