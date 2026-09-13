# TV 播放器快捷選單設計

日期：2026-09-14
狀態：設計確認，待實作
相關：`docs/2026-09-13-fix-plan.md` 的 #7b

---

## 要解決的問題

TV 上播放中**沒有任何換集／換線的入口**。想跳到第 87 集、或當前線路卡頓想換一條，都只能按 BACK 退回詳情頁重選——而退回去會中斷播放，重選後又要重新載入。

現況對照：

| | 換集 | 換線 |
|---|---|---|
| 手機（`EmbeddedPlayerView`） | 有（控制列的 ⏮ ⏭，只能上一集／下一集） | 無 |
| TV（`PlayerScreen` 的 `isTV` 分支） | **無** | **無** |

TV 分支目前只有 `PlayerView` 加一個 3.5 秒自動隱藏的資訊列（片名／集數／線路名）。

---

## 核心設計決定：按鍵不做焦點轉移

**選單只負責顯示，不接受焦點。** 焦點始終留在 `PlayerView` 上，所有按鍵由既有的 `setOnKeyListener` 統一分派，依 `menuOpen` 狀態決定行為。

這是刻意的取捨。`#7` 才剛證實過：Compose 與 View 之間的焦點交接是這個播放器最容易出錯的地方——`setOnKeyListener` 裡的快轉與 BACK 分支死了好幾個版本沒人發現，原因就是 Compose 端沒有 `requestFocus`，事件根本進不了 PlayerView 的階層。再加一個會搶焦點的 Compose 選單，等於把同一個坑再挖一次。

不轉移焦點的額外好處：不必處理「選單關閉後焦點要還給誰」，也不會與 media3 控制列的 `FOCUS_BLOCK_DESCENDANTS` 打架。

### 按鍵分派表

| 按鍵 | 選單關閉（現況，不變） | 選單開啟 |
|---|---|---|
| `UP` | **開啟選單**，游標落在「集數」列 ¹ | 切到「線路」列 |
| `DOWN` | 無動作 | 切到「集數」列 |
| `LEFT` / `RIGHT` | −10s / +10s 快轉 | 在目前這列內左右移動 |
| `OK` / `ENTER` | 暫停／播放 | **選定**目前這一項 |
| `BACK` | 存進度並返回詳情頁 | **只關閉選單**，不離開播放器 |

¹ 預設落在集數列，是因為它的項目多、需要捲動定位；線路列通常十幾項且一眼看得完，多按一次 `UP` 的成本低。

> `BACK` 在選單開啟時只關選單，代表要離開播放器得按兩次。這符合 TV 慣例（Netflix、YouTube 都是如此）：BACK 一律先收掉最上層的 UI。

---

## 元件

### `PlayerQuickMenu`（新增，純顯示）

位置：`ui/player/PlayerQuickMenu.kt`

```kotlin
@Composable
fun PlayerQuickMenu(
    visible: Boolean,
    sources: List<SourceRow>,       // sourceId + sourceName + 是否為當前
    episodes: List<EpisodeRow>,     // number + title + 是否為當前
    focusedRow: MenuRow,            // SOURCE 或 EPISODE
    focusedIndex: Int,
    modifier: Modifier = Modifier,
)
```

- **不含任何 `Modifier.focusable`、`onKeyEvent` 或 `FocusRequester`**。它是被動的，狀態由 `PlayerScreen` 傳入。
- 從底部滑入（`AnimatedVisibility` + `slideInVertically`），半透明深色背景，影片在底下繼續播。
- 兩列都用 `LazyRow`。集數列用 `rememberLazyListState`，`visible` 轉為 true 時 `scrollToItem(currentEpisodeIndex)`——一叫出來就停在目前那集（265 集的番不必從第 1 集捲起）。
- **兩種狀態要分開畫**，因為沒有真正的 View 焦點，全部得自己來：
  - 「當前正在播的」用 `CinemaRed` 填色（與詳情頁的集數格線一致）
  - 「游標所在的」用 1.5dp 白色外框
  - 兩者可能同時出現在同一項——一叫出選單時就是這個狀態

### `PlayerScreen` 的新增狀態

全部放在 composable 的 `remember` 裡，**不進 ViewModel**——這些是純 UI 狀態，process death 後不需要還原。

```kotlin
var menuOpen by remember { mutableStateOf(false) }
var menuRow by remember { mutableStateOf(MenuRow.EPISODE) }
var menuIndex by remember { mutableIntStateOf(0) }
var menuTouchedAt by remember { mutableLongStateOf(0L) }   // 自動隱藏用
```

---

## 資料流

```
vodDetail.episodes  ──┬─→ sources：每個 EpisodeGroup 的 sourceId / sourceName
                      │
                      └─→ episodes：當前 sourceId 那組的 episodes
uiState.sourceId    ────→ 標出當前線路
uiState.episodeNum  ────→ 標出當前集數、決定 scrollToItem 的位置
```

選定後：

- **選線路** → `viewModel.switchSource(sourceId)` → 關閉選單
  播放進度會被帶過去（`#10` 已實作：`switchSource` 讀 `resumePositionMs`，並在 `STATE_READY` 後依實際 duration 夾住）
- **選集數** → `viewModel.switchEpisode(num)` → 關閉選單
  換集從頭播，這是既有且正確的行為

---

## 自動隱藏

8 秒無操作自動收起，**任何按鍵都重置計時**。

- 比 media3 控制列的 5 秒長一些，留出「要選哪一集」的思考時間
- 實作：`LaunchedEffect(menuOpen, menuTouchedAt) { if (menuOpen) { delay(8000); menuOpen = false } }`
  每次按鍵更新 `menuTouchedAt`，key 變動就重新計時

開啟選單時呼叫 `playerView?.hideController()`，避免 media3 控制列與選單疊在一起。

---

## 錯誤處理

| 情況 | 行為 |
|---|---|
| `vodDetail` 還沒載入（`null`） | `UP` 不開選單（沒有資料可選） |
| 該線路沒有當前集數 | 集數列照常顯示該線路實際有的集數；`switchSource` 內部本來就會處理集號對不上的情況 |
| 選到與當前相同的線路／集數 | 直接關閉選單，不觸發切換 |
| 切換過程中（`isLoading`） | 選單已關閉，載入覆蓋層照現有邏輯顯示 |
| 播放失敗（`error != null`） | 錯誤覆蓋層顯示時不允許開選單——PlayerView 此時已不在樹上（`#7` 加了 `error == null` 條件），拿不到按鍵 |

---

## 測試

**可用 JVM 單元測試的部分**（純函式，抽出來測）：

- 按鍵分派：給定 `menuOpen` / `menuRow` / `menuIndex` 與一個 keyCode，回傳「新狀態 + 要執行的動作」。抽成 `fun reduceMenuKey(state, keyCode): MenuKeyResult`，這是整個功能唯一有分支邏輯的地方，值得測。
- 邊界：`menuIndex` 在列首按 LEFT、列尾按 RIGHT 時不應越界。

**只能上機驗的部分**（列在實作計畫的驗收步驟）：

- `UP` 開啟選單且定位在當前集
- 選單開啟時 `LEFT/RIGHT` 不再快轉（用 `positionMs` 比對，方法同 `#7` 的驗證）
- 選線路後播放位置有被帶過去
- `BACK` 第一次關選單、第二次才離開播放器
- 8 秒後自動收起

---

## 不做（YAGNI）

- **不做搜尋／跳頁**：使用者選的是「直接捲，從目前集數開始」
- **不動手機版**：它已經有上一集／下一集，而且是觸控操作，需求不同
- **不把選單狀態放進 ViewModel**：純 UI 狀態，process death 後重新開啟即可
- **不做「上一集／下一集」快捷鍵**：自動播下一集已經涵蓋主要情境，多綁按鍵反而增加誤觸
