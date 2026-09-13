# TV 播放器快捷選單 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 讓 TV 使用者在播放中直接換集／換線，不必退回詳情頁重選。

**Architecture:** 選單是純顯示的 Compose 元件，**不接受焦點**；所有按鍵仍由 `PlayerView` 既有的 `setOnKeyListener` 統一分派，依選單開關狀態決定行為。按鍵決策抽成無 Android 依賴的純函式 `reduceMenuKey`，用 JVM 單元測試覆蓋。

**Tech Stack:** Kotlin、Jetpack Compose、Media3 ExoPlayer、JUnit4 + Truth

**Spec:** `docs/superpowers/specs/2026-09-14-tv-player-quick-menu-design.md`

---

## 檔案結構

| 檔案 | 責任 |
|---|---|
| `app/src/main/java/com/gimy/tv/ui/player/PlayerQuickMenu.kt`（新增） | 選單的狀態型別、按鍵分派純函式、以及純顯示的 Composable |
| `app/src/test/kotlin/com/gimy/tv/ui/player/QuickMenuKeyTest.kt`（新增） | `reduceMenuKey` 的單元測試 |
| `app/src/main/java/com/gimy/tv/ui/player/PlayerScreen.kt`（修改） | 持有選單狀態、在 `setOnKeyListener` 內接上 `reduceMenuKey`、渲染選單 |

放同一個檔案是因為型別、純函式與 Composable 是同一個功能單元，會一起改。測試只 import 純函式的部分，不需要 Compose runtime。

---

### Task 1: 按鍵分派純函式

**Files:**
- Create: `app/src/main/java/com/gimy/tv/ui/player/PlayerQuickMenu.kt`
- Test: `app/src/test/kotlin/com/gimy/tv/ui/player/QuickMenuKeyTest.kt`

- [ ] **Step 1: 寫失敗的測試**

建立 `app/src/test/kotlin/com/gimy/tv/ui/player/QuickMenuKeyTest.kt`：

```kotlin
package com.gimy.tv.ui.player

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 釘住快捷選單的按鍵行為。
 *
 * 為什麼需要：這是整個播放器唯一有分支邏輯的按鍵處理，而播放器的按鍵在
 * 2026-09-13 的稽核中被發現整組是死碼（Compose 端沒有 requestFocus，事件
 * 根本進不了 PlayerView）。那種錯誤沒有測試就只能靠上機按按看才會發現。
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
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `./gradlew testDebugUnitTest --tests "*QuickMenuKeyTest*"`
Expected: FAIL，錯誤是 `Unresolved reference: QuickMenuContext`（型別還不存在）

- [ ] **Step 3: 寫最小實作**

建立 `app/src/main/java/com/gimy/tv/ui/player/PlayerQuickMenu.kt`：

```kotlin
package com.gimy.tv.ui.player

import android.view.KeyEvent

/** 選單目前停在哪一列。 */
enum class MenuRow { SOURCE, EPISODE }

/** 選單的 UI 狀態。純 UI，不進 ViewModel。 */
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
 * 選單的按鍵分派。刻意寫成純函式：播放器的按鍵處理在 2026-09-13 被發現整組是
 * 死碼卻沒人發現，這裡把唯一有分支的部分抽出來，讓它能被單元測試釘住。
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
            MenuKeyResult(state.copy(index = (state.index - 1).coerceAtLeast(0)), MenuAction.Consumed)

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
            if (isCurrent) MenuKeyResult(closedState, MenuAction.Consumed)
            else when (state.row) {
                MenuRow.SOURCE -> MenuKeyResult(closedState, MenuAction.PickSource(state.index))
                MenuRow.EPISODE -> MenuKeyResult(closedState, MenuAction.PickEpisode(state.index))
            }
        }

        // BACK 先收掉最上層的 UI，不離開播放器——TV 上的通則。
        KeyEvent.KEYCODE_BACK -> MenuKeyResult(state.copy(open = false), MenuAction.Consumed)

        else -> MenuKeyResult(state, MenuAction.Consumed)
    }
}
```

- [ ] **Step 4: 執行測試確認通過**

Run: `./gradlew testDebugUnitTest --tests "*QuickMenuKeyTest*"`
Expected: PASS，9 個測試全過

- [ ] **Step 5: 確認沒有弄壞既有測試**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL，總數從 157 增加到 166

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/gimy/tv/ui/player/PlayerQuickMenu.kt \
        app/src/test/kotlin/com/gimy/tv/ui/player/QuickMenuKeyTest.kt
git commit -F- <<'MSG'
新增 TV 快捷選單的按鍵分派邏輯

抽成純函式並用單元測試釘住。播放器的按鍵處理在稽核中被發現整組是死碼卻沒人
察覺，正是因為那段邏輯沒有任何測試護欄。
MSG
```

---

### Task 2: 選單的顯示元件

**Files:**
- Modify: `app/src/main/java/com/gimy/tv/ui/player/PlayerQuickMenu.kt`（在 Task 1 建立的檔案末尾追加）

- [ ] **Step 1: 追加 Composable**

先把 `PlayerQuickMenu.kt` 的第 3 行 `import android.view.KeyEvent` 替換成這整段 import：

```kotlin
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
```

然後在檔案末尾追加：

```kotlin

/** 選單裡一個可選項目的顯示資料。 */
data class QuickMenuItem(val label: String, val isCurrent: Boolean)

/**
 * 播放中的快捷選單。**純顯示**——不含 focusable / onKeyEvent / FocusRequester。
 *
 * 焦點始終留在 PlayerView 上，按鍵由 PlayerScreen 的 setOnKeyListener 統一分派。
 * 這是刻意的：Compose 與 View 的焦點交接是這個播放器出過最多問題的地方。
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
    // 游標移到哪就捲到哪；一叫出選單時這會讓它直接停在目前那一集。
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
                        .border(
                            // 沒有真正的 View 焦點，游標得自己畫。
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
```

- [ ] **Step 2: 確認編譯通過**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/gimy/tv/ui/player/PlayerQuickMenu.kt
git commit -F- <<'MSG'
新增 TV 快捷選單的顯示元件

從底部滑入、半透明，影片在底下繼續播。刻意不含 focusable 與 onKeyEvent：
焦點留在 PlayerView，這個元件只負責畫出來。
MSG
```

---

### Task 3: 接到 PlayerScreen

**Files:**
- Modify: `app/src/main/java/com/gimy/tv/ui/player/PlayerScreen.kt`

- [ ] **Step 1: 加入選單狀態**

在 `PlayerScreen` 裡、既有的 `var pendingResumeMs by remember { ... }` 之後加：

```kotlin
    // 快捷選單的狀態。純 UI，不進 ViewModel——process death 後重新叫出來即可。
    var menuState by remember { mutableStateOf(QuickMenuState()) }
    // 每次按鍵都更新，用來重新計算自動隱藏的 8 秒。
    var menuTouchedAt by remember { mutableLongStateOf(0L) }
```

- [ ] **Step 3: 組出選單要顯示的資料**（先做下面的 Step 2，這裡才編譯得過）

在 `Box(Modifier.fillMaxSize()...)` 之前加：

```kotlin
    // 線路來自 vodDetail 的 EpisodeGroup；集數是當前線路那一組。
    val menuSources = uiState.sourceOptions.map {
        QuickMenuItem(label = it.sourceName, isCurrent = it.sourceId == uiState.sourceId)
    }
    val menuEpisodes = uiState.episodeOptions.map {
        QuickMenuItem(
            label = it.title.ifBlank { "第${it.number}集" },
            isCurrent = it.number == uiState.episodeNum,
        )
    }
    val menuCtx = QuickMenuContext(
        sourceCount = menuSources.size,
        episodeCount = menuEpisodes.size,
        currentSourceIndex = menuSources.indexOfFirst { it.isCurrent }.coerceAtLeast(0),
        currentEpisodeIndex = menuEpisodes.indexOfFirst { it.isCurrent }.coerceAtLeast(0),
    )
```

- [ ] **Step 2: 先在 ViewModel 暴露選單需要的清單**

修改 `app/src/main/java/com/gimy/tv/ui/player/PlayerViewModel.kt`：

在 `PlayerUiState` 加兩個欄位（預設空清單，不影響既有建構點）：

```kotlin
    /** 快捷選單用：這部片有哪些線路。順序與 orderedSourcesFrom 無關，用 vodDetail 的原順序。 */
    val sourceOptions: List<EpisodeGroup> = emptyList(),
    /** 快捷選單用：當前線路的集數清單。 */
    val episodeOptions: List<Episode> = emptyList(),
```

在 `PlayerViewModel.kt:19` 的 `data class PlayerUiState` 內加上這兩個欄位，然後找到 `playWithFallback` 裡**唯一**那個同時設定 `sourceId` 與 `episodeNum` 的 `_uiState.update`（約 `:218-229`，以 `resumePositionMs = if (i == 0) resumeMs else 0L` 這行為辨識特徵），在 `episodeKind = ep.kind,` 後面補上：

```kotlin
                        sourceOptions = detail.episodes,
                        episodeOptions = detail.episodes
                            .firstOrNull { it.sourceId == group.sourceId }
                            ?.episodes
                            ?: emptyList(),
```

- [ ] **Step 4: 在 setOnKeyListener 最前面接上分派**

修改 `PlayerScreen.kt` 的 `setOnKeyListener`，在 `if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false` 之後、`when (keyCode)` 之前插入：

```kotlin
                                // 選單的按鍵優先。reduceMenuKey 回 PassThrough 才輪到
                                // 下面的播放器操作（快轉、暫停、返回）。
                                val menuResult = reduceMenuKey(menuState, keyCode, menuCtx)
                                if (menuResult.action != MenuAction.PassThrough) {
                                    menuState = menuResult.state
                                    menuTouchedAt = System.currentTimeMillis()
                                    if (menuState.open) hideController()
                                    when (val a = menuResult.action) {
                                        is MenuAction.PickSource ->
                                            uiState.sourceOptions.getOrNull(a.index)
                                                ?.let { viewModel.switchSource(it.sourceId) }
                                        is MenuAction.PickEpisode ->
                                            uiState.episodeOptions.getOrNull(a.index)
                                                ?.let { viewModel.switchEpisode(it.number) }
                                        else -> Unit
                                    }
                                    return@setOnKeyListener true
                                }
```

> `showController().let { hideController() }` 是為了讓 media3 控制列在選單開啟時收起，避免兩層 UI 疊在一起。

- [ ] **Step 5: 渲染選單並加上自動隱藏**

在 `isTV` 分支內、資訊列的 `AnimatedVisibility` 之後加：

```kotlin
            PlayerQuickMenu(
                state = menuState,
                sources = menuSources,
                episodes = menuEpisodes,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
```

並在 `PlayerScreen` 的 effect 區（與其他 `LaunchedEffect` 放一起）加：

```kotlin
    // 8 秒沒動作就收起來。任何按鍵都會更新 menuTouchedAt，key 一變就重新計時。
    LaunchedEffect(menuState.open, menuTouchedAt) {
        if (menuState.open) {
            delay(8000)
            menuState = menuState.copy(open = false)
        }
    }
```

- [ ] **Step 6: 確認編譯與既有測試**

Run: `./gradlew compileDebugKotlin && ./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL，166 個測試全過

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/gimy/tv/ui/player/PlayerScreen.kt \
        app/src/main/java/com/gimy/tv/ui/player/PlayerViewModel.kt
git commit -F- <<'MSG'
TV 播放中可直接換集與換線

按上鍵叫出選單，游標停在目前這一集；上下切換線路列與集數列，左右移動，
OK 選定，BACK 只收選單不離開播放器。8 秒沒動作自動收起。

選單不接受焦點，按鍵仍由 PlayerView 既有的 listener 依選單狀態分派。
MSG
```

---

### Task 4: 上機驗收

**Files:** 無（純驗證）

- [ ] **Step 1: 編譯並安裝到 TV 模擬器**

```bash
~/Library/Android/sdk/emulator/emulator -avd Television_4K -no-boot-anim &
./gradlew assembleDebug
until adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -q "^1$"; do sleep 5; done
adb -s emulator-5554 install -r -t app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: 逐項確認**

播放任一集後，依序檢查：

| 操作 | 預期 |
|---|---|
| 按 `UP` | 選單從底部滑入，集數列的游標停在**目前這一集**（不是第 1 集） |
| 按 `LEFT` / `RIGHT` | 游標在集數列移動，**畫面沒有快轉** |
| 按 `UP` | 游標跳到線路列，停在目前線路 |
| 按 `OK`（選另一條線） | 選單收起、換線，且**播放位置有帶過去** |
| 按 `BACK`（選單開啟時） | 只收起選單，**沒有離開播放器** |
| 再按 `BACK` | 離開播放器，回到詳情頁 |
| 開啟選單後不動 8 秒 | 自動收起 |

- [ ] **Step 3: 確認選單關閉後快轉仍正常**

用與稽核時相同的量化方法（避免「看起來有動」的誤判）：

```bash
# 記下 positionMs，按 30 次右鍵，再記一次
adb -s emulator-5554 exec-out run-as com.gimy.tv cat databases/gimy_tv.db >| /tmp/db1
sqlite3 /tmp/db1 "SELECT positionMs FROM watch_history ORDER BY updatedAt DESC LIMIT 1;"
adb -s emulator-5554 shell "i=0; while [ \$i -lt 30 ]; do input keyevent 22; i=\$((i+1)); done"
sleep 16
adb -s emulator-5554 exec-out run-as com.gimy.tv cat databases/gimy_tv.db >| /tmp/db2
sqlite3 /tmp/db2 "SELECT positionMs FROM watch_history ORDER BY updatedAt DESC LIMIT 1;"
```

Expected: 兩次相差約 300 秒（選單關閉時快轉照常運作）

- [ ] **Step 4: 測完關掉模擬器**

```bash
adb -s emulator-5554 emu kill
```

---

## 風險與回退

| 風險 | 徵兆 | 處理 |
|---|---|---|
| 選單開啟時左右鍵仍在快轉 | 畫面跳動 | `reduceMenuKey` 的 `PassThrough` 判斷有誤，看 Task 1 的測試是否涵蓋該情境 |
| `BACK` 一按就離開播放器 | 選單沒收起就跳回詳情頁 | `setOnKeyListener` 的分派沒放在 `when` 之前，或沒 `return true` |
| 選單開啟時 media3 控制列也跑出來 | 兩層 UI 疊住 | Step 4 的 `hideController()` 沒生效 |
| 換線後從頭播 | 進度沒帶 | 與本功能無關，是 `#10` 的 `switchSource`；先確認該 commit 在不在 |

整個功能是新增的，若驗收不過可直接 `git revert` Task 1–3 的三個 commit，不影響其他修復。
