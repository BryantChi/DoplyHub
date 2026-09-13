package com.gimy.tv.ui.player

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gimy.tv.ui.theme.CinemaRed
import com.gimy.tv.ui.theme.CinemaTextMuted

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

/** 選單裡一個可選項目的顯示資料。 */
data class QuickMenuItem(val label: String, val isCurrent: Boolean)

/**
 * 播放中的快捷選單。**純顯示**——不含 focusable / onKeyEvent / FocusRequester。
 *
 * 焦點始終留在 PlayerView 上，按鍵由 PlayerScreen 的 setOnKeyListener 統一分派。
 * 這是刻意的：Compose 與 View 的焦點交接是這個播放器出過最多問題的地方，
 * 再放一個會搶焦點的 Compose 元件進來等於把同一個坑重挖一次。
 */
@Composable
fun PlayerQuickMenu(
    state: QuickMenuState,
    sources: List<QuickMenuItem>,
    episodes: List<QuickMenuItem>,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state.open,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.85f))
                .padding(horizontal = 48.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            QuickMenuRow(
                label = "線路",
                items = sources,
                focusedIndex = if (state.row == MenuRow.SOURCE) state.index else -1,
            )
            QuickMenuRow(
                label = "集數",
                items = episodes,
                focusedIndex = if (state.row == MenuRow.EPISODE) state.index else -1,
            )
            Text(
                "▲▼ 切換　◀▶ 移動　OK 選定　BACK 關閉",
                color = CinemaTextMuted,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun QuickMenuRow(
    label: String,
    items: List<QuickMenuItem>,
    focusedIndex: Int,
) {
    val listState = rememberLazyListState()
    // 游標移到哪就捲到哪。一叫出選單時，這會讓集數列直接停在目前那一集——
    // 265 集的番不必從第 1 集捲起。
    LaunchedEffect(focusedIndex) {
        if (focusedIndex >= 0 && focusedIndex < items.size) {
            listState.scrollToItem(focusedIndex)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = CinemaTextMuted,
            fontSize = 12.sp,
            modifier = Modifier.width(48.dp),
        )
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items.size) { i ->
                val item = items[i]
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (item.isCurrent) CinemaRed else Color.White.copy(alpha = 0.12f))
                        // 沒有真正的 View 焦點，游標得自己畫：
                        //   正在播的 → 紅底（與詳情頁集數格線一致）
                        //   游標所在 → 白框
                        .border(
                            BorderStroke(1.5.dp, if (i == focusedIndex) Color.White else Color.Transparent),
                            RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        item.label,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = if (item.isCurrent) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}
