package com.gimy.tv.ui.player

import android.view.KeyEvent

/** 選單目前停在哪一列。 */
enum class MenuRow { SOURCE, EPISODE }

/** 選單的 UI 狀態。純 UI，不進 ViewModel——process death 後重新叫出來即可。 */
data class QuickMenuState(
    val open: Boolean = false,
    val row: MenuRow = MenuRow.EPISODE,
    val index: Int = 0,
)

/** 分派按鍵時需要知道的外部資訊。 */
data class QuickMenuContext(
    val sourceCount: Int,
    val episodeCount: Int,
    val currentSourceIndex: Int,
    val currentEpisodeIndex: Int,
)

/** 按鍵造成的後果。[PassThrough] 代表交還給播放器既有的處理（快轉、暫停、返回）。 */
sealed interface MenuAction {
    data object PassThrough : MenuAction

    /** 事件已消費，只有選單狀態改變，呼叫端不需要做別的。 */
    data object Consumed : MenuAction
    data class PickSource(val index: Int) : MenuAction
    data class PickEpisode(val index: Int) : MenuAction
}

data class MenuKeyResult(val state: QuickMenuState, val action: MenuAction)

/**
 * 選單的按鍵分派。
 *
 * 刻意寫成純函式：播放器的按鍵處理在 2026-09-13 的稽核中被發現整組是死碼，
 * 卻因為沒有任何測試護欄而長期沒人察覺。把唯一有分支的部分抽出來，讓它能被
 * JVM 單元測試釘住。
 */
fun reduceMenuKey(
    state: QuickMenuState,
    keyCode: Int,
    ctx: QuickMenuContext,
): MenuKeyResult {
    if (!state.open) {
        // 沒東西可選就別開，免得開出一個空選單擋住畫面。
        val canOpen = ctx.sourceCount > 0 || ctx.episodeCount > 0
        return if (keyCode == KeyEvent.KEYCODE_DPAD_UP && canOpen) {
            MenuKeyResult(
                QuickMenuState(open = true, row = MenuRow.EPISODE, index = ctx.currentEpisodeIndex),
                MenuAction.Consumed,
            )
        } else {
            MenuKeyResult(state, MenuAction.PassThrough)
        }
    }

    val lastIndex = when (state.row) {
        MenuRow.SOURCE -> ctx.sourceCount - 1
        MenuRow.EPISODE -> ctx.episodeCount - 1
    }

    return when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP ->
            MenuKeyResult(
                state.copy(row = MenuRow.SOURCE, index = ctx.currentSourceIndex),
                MenuAction.Consumed,
            )

        KeyEvent.KEYCODE_DPAD_DOWN ->
            MenuKeyResult(
                state.copy(row = MenuRow.EPISODE, index = ctx.currentEpisodeIndex),
                MenuAction.Consumed,
            )

        KeyEvent.KEYCODE_DPAD_LEFT ->
            MenuKeyResult(
                state.copy(index = (state.index - 1).coerceAtLeast(0)),
                MenuAction.Consumed,
            )

        KeyEvent.KEYCODE_DPAD_RIGHT ->
            MenuKeyResult(
                state.copy(index = (state.index + 1).coerceAtMost(lastIndex.coerceAtLeast(0))),
                MenuAction.Consumed,
            )

        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
            val closedState = state.copy(open = false)
            val isCurrent = when (state.row) {
                MenuRow.SOURCE -> state.index == ctx.currentSourceIndex
                MenuRow.EPISODE -> state.index == ctx.currentEpisodeIndex
            }
            // 選到正在播的那一項就只收起選單，不要白跑一次重新取流。
            if (isCurrent) {
                MenuKeyResult(closedState, MenuAction.Consumed)
            } else when (state.row) {
                MenuRow.SOURCE -> MenuKeyResult(closedState, MenuAction.PickSource(state.index))
                MenuRow.EPISODE -> MenuKeyResult(closedState, MenuAction.PickEpisode(state.index))
            }
        }

        // BACK 先收掉最上層的 UI，不離開播放器——TV 上的通則。
        KeyEvent.KEYCODE_BACK -> MenuKeyResult(state.copy(open = false), MenuAction.Consumed)

        else -> MenuKeyResult(state, MenuAction.Consumed)
    }
}
