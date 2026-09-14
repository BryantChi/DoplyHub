package com.gimy.tv.ui.home

import android.content.Context
import com.gimy.tv.R
import dagger.hilt.android.qualifiers.ApplicationContext

import kotlinx.coroutines.Job
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gimy.tv.domain.repository.CacheManager
import com.gimy.tv.domain.model.categoryMap
import com.gimy.tv.domain.model.StandardCategory
import com.gimy.tv.domain.model.*
import com.gimy.tv.domain.repository.HomeRowData
import com.gimy.tv.domain.repository.VodRepository
import com.gimy.tv.domain.repository.WatchHistoryEntry
import com.gimy.tv.domain.repository.WatchHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeRow(
    val title: String,
    val typeId: Int,
    val items: List<Vod>,
    val sourceType: SourceType = SourceType.GIMYTV
)

data class HomeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val rows: List<HomeRow> = emptyList(),
    val continueWatching: List<WatchHistoryEntry> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val vodRepository: VodRepository,
    private val watchHistoryRepository: WatchHistoryRepository,
    private val cacheManager: CacheManager,
    sourcePreferencesRepository: com.gimy.tv.domain.repository.SourcePreferences,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val enabledSources: kotlinx.coroutines.flow.StateFlow<Set<SourceType>> =
        sourcePreferencesRepository.enabledSources

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /**
     * Per-(source,typeId) lazy-loaded "More Sources" rows.
     * Each row fetches once on first access; survives until refresh() clears them.
     * Phase 3.1 lets the home page surface the 5 new MacCMS sources without blocking
     * the primary load — the rows materialize as the user scrolls down.
     */
    // 只在主執行緒被碰：讀取來自組合階段，clear 來自 refresh／retry，兩者都在 Main。
    // 所以這裡不需要 concurrent 容器，但在途的抓取需要能取消——見 clearMoreSourceRows。
    private val moreSourceCache = mutableMapOf<String, MutableStateFlow<MoreSourceRowState>>()
    private val moreSourceJobs = mutableMapOf<String, Job>()

    /** 純讀取，不發請求。抓取由畫面的 LaunchedEffect 觸發（見 [ensureMoreSourceRow]）。 */
    fun moreSourceRow(sourceType: SourceType, typeId: Int): StateFlow<MoreSourceRowState> =
        moreSourceCache.getOrPut("${sourceType.name}_$typeId") {
            MutableStateFlow(MoreSourceRowState.Loading)
        }

    /** 這一列第一次進入畫面時抓資料。重複呼叫是安全的：已經有結果就不再打網路。 */
    fun ensureMoreSourceRow(sourceType: SourceType, typeId: Int) {
        val key = "${sourceType.name}_$typeId"
        moreSourceRow(sourceType, typeId)  // 確保 flow 存在
        val flow = moreSourceCache[key] ?: return
        if (flow.value !is MoreSourceRowState.Loading) return
        if (moreSourceJobs[key]?.isActive == true) return
        fetchMoreSourceRow(key, sourceType, typeId, flow)
    }

    /** 讓失敗的那一列自己重來，不必整個首頁重新整理（其他列是好的，不該一起丟掉）。 */
    fun retryMoreSourceRow(sourceType: SourceType, typeId: Int) {
        val key = "${sourceType.name}_$typeId"
        val flow = moreSourceCache[key] ?: return
        if (flow.value == MoreSourceRowState.Loading) return
        flow.value = MoreSourceRowState.Loading
        fetchMoreSourceRow(key, sourceType, typeId, flow)
    }

    /**
     * 清掉所有「更多來源」的列，並取消在途的抓取。
     *
     * 只 clear map 不取消 job 的話，那幾個請求會繼續跑完再寫進沒人看的 flow，
     * 而且正好與重試時的首頁重載搶同一組連線額度——這個專案吃過好幾次這種虧。
     */
    private fun clearMoreSourceRows() {
        moreSourceJobs.values.forEach { it.cancel() }
        moreSourceJobs.clear()
        moreSourceCache.clear()
    }

    private fun fetchMoreSourceRow(
        key: String,
        sourceType: SourceType,
        typeId: Int,
        flow: MutableStateFlow<MoreSourceRowState>,
    ) {
        moreSourceJobs[key]?.cancel()
        moreSourceJobs[key] = viewModelScope.launch {
            flow.value = try {
                MoreSourceRowState.Loaded(
                    vodRepository.getVodList(sourceType, typeId, 1).items.take(15)
                )
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("HomeLoad", "更多來源 $sourceType($typeId) failed", e)
                MoreSourceRowState.Failed
            }
        }
    }

    /**
     * movieffm typeId → 對應的 gimy typeId，用來把 FFM 的列插在同分類的 gimy 列後面。
     *
     * 由兩張 categoryMap 推導，不再手寫。手寫的那一份把 204（日劇）配到 gimy 21（港劇）、
     * 208（港劇）配到 gimy 15（日劇），正是 2026-09-13 那個對調錯誤的同一個形狀，
     * 只是躲在另一個檔案裡，連測試都沒蓋到。
     */
    private val ffmToGimyMap: Map<Int, Int> = StandardCategory.entries.mapNotNull { cat ->
        val ffm = SourceType.MOVIEFFM.categoryMap.typeIdFor(cat)
        val gimy = SourceType.GIMYTV.categoryMap.typeIdFor(cat)
        if (ffm > 0 && gimy > 0) ffm to gimy else null
    }.toMap()

    init {
        loadHome()
        observeContinueWatching()
    }

    private fun observeContinueWatching() {
        viewModelScope.launch {
            watchHistoryRepository.getRecentHistory(10).collect { entries ->
                _uiState.update { it.copy(continueWatching = entries) }
            }
        }
    }

    fun loadHome() {
        fetchHome(isRefresh = false, force = false)
    }

    fun refresh() {
        if (_uiState.value.isRefreshing) return
        clearMoreSourceRows()  // force "More Sources" rows to re-fetch on next collect
        fetchHome(isRefresh = true, force = true)
    }

    /**
     * 「載入失敗」畫面上的重試。跟 loadHome() 的差別是它會先把所有層級的快取清掉
     * （HTTP 回應、首頁／搜尋／詳情的記憶體快取、封面圖），並「等」鏡像重新探測完成，
     * 再用 force=true 重抓。
     *
     * 原本這裡直接呼叫 loadHome()，等於用同一個（可能已失效的）網址、同一份 HTTP 快取
     * 再打一次，所以鏡像掛掉時按幾次重試都是同樣結果。EndpointResolver 的 resolved 會
     * 存進 DataStore 且 TTL 24 小時，只有 refresh() 路徑會重新探測——錯誤畫面反而是
     * 唯一碰不到重新探測的入口。
     *
     * 用 isRefresh=false 是為了保留載入中／錯誤訊息的 UI；isRefresh=true 那條路徑
     * 假設畫面上已有內容可以留著，在空畫面重試時會把錯誤訊息吃掉變成一片空白。
     */
    fun retry() {
        clearMoreSourceRows()
        viewModelScope.launch {
            // 先進載入中，清快取這段也要有畫面回饋，否則按下去像是沒反應。
            _uiState.update { it.copy(isLoading = true, error = null) }
            // 等重新探測跑完再抓：只丟背景刷新的話當次仍用舊網址，使用者得按第二次才生效。
            runCatching { cacheManager.clearAll(clearImages = false) }
            fetchHome(isRefresh = false, force = true)
        }
    }

    /**
     * @param isRefresh 走下拉重整的 UI（保留現有內容、顯示重整條）而非全螢幕載入中。
     * @param force 讓資料層真的重抓：清 HTTP 快取 + 重新探測端點，而不是吃記憶體快取。
     */
    private fun fetchHome(isRefresh: Boolean, force: Boolean) {
        // Snapshot so we can preserve visible content if refresh produces nothing
        val previousRows = _uiState.value.rows

        // Phase 1: Load gimymax (fast — show immediately)
        val gimyJob = viewModelScope.launch {
            _uiState.update {
                if (isRefresh) it.copy(isRefreshing = true, error = null)
                else it.copy(isLoading = true, error = null)
            }
            try {
                val gimyRows = vodRepository.getGimyHomeRows(forceRefresh = force)
                    .filter { it.items.isNotEmpty() }
                    .map { HomeRow(it.title, it.typeId, it.items, it.sourceType) }
                android.util.Log.w("HomeLoad", "gimy ok: ${gimyRows.size} rows (force=$force)")

                if (gimyRows.isNotEmpty()) {
                    // error = null 是必要的：Phase 2 可能已經搶先把「無法載入內容」寫進去了
                    // （它失敗得快，常比這裡早回來），不清掉的話畫面會被錯誤蓋住，
                    // 即使這 10 列資料已經在手上。
                    _uiState.update { it.copy(isLoading = false, rows = gimyRows, error = null) }
                } else if (!isRefresh) {
                    // Phase 1 returned nothing — keep loading, let Phase 2 try
                    _uiState.update { it.copy(rows = emptyList()) }
                }
                // On refresh: leave previous rows untouched until Phase 2 reports
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("HomeLoad", "gimy FAILED (force=$force)", e)
                if (!isRefresh) {
                    // Don't set isLoading=false yet — Phase 2 might still succeed
                    _uiState.update { it.copy(rows = emptyList()) }
                }
                // On refresh failure: keep previous rows visible
            }
        }

        // Phase 2: Load movieffm in background (append when ready)
        viewModelScope.launch {
            try {
                val ffmRows = vodRepository.getMovieffmHomeRows(forceRefresh = force)
                    .filter { it.items.isNotEmpty() }
                android.util.Log.w("HomeLoad", "ffm ok: ${ffmRows.size} rows (force=$force)")

                // ffm 空的時候，「是不是整頁都載不到」必須看 Phase 1 的結果，不能用當下的
                // state.rows 判斷：ffm 失敗得快，幾乎總是比 gimy 早回來，那一刻 rows 還是空的，
                // 於是把還在路上的 gimy 結果誤判成全部失敗，寫下錯誤畫面蓋住後到的內容。
                // 只在空的時候才等，有內容就照舊立即併入，不拖慢正常情況。
                if (ffmRows.isEmpty()) gimyJob.join()

                _uiState.update { state ->
                    if (ffmRows.isNotEmpty()) {
                        val merged = interleaveRows(state.rows, ffmRows)
                        state.copy(isLoading = false, isRefreshing = false, rows = merged)
                    } else if (state.rows.isEmpty()) {
                        if (isRefresh && previousRows.isNotEmpty()) {
                            state.copy(isLoading = false, isRefreshing = false, rows = previousRows)
                        } else {
                            state.copy(isLoading = false, isRefreshing = false, error = context.getString(R.string.common_network_error))
                        }
                    } else {
                        state.copy(isLoading = false, isRefreshing = false)
                    }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("HomeLoad", "ffm FAILED (force=$force)", e)
                // 同上：ffm 掛掉不代表整頁沒東西，要等 Phase 1 的結果才能下結論。
                gimyJob.join()
                _uiState.update { state ->
                    when {
                        isRefresh && state.rows.isEmpty() && previousRows.isNotEmpty() ->
                            state.copy(isLoading = false, isRefreshing = false, rows = previousRows)
                        state.rows.isEmpty() && state.isLoading ->
                            state.copy(isLoading = false, isRefreshing = false, error = context.getString(R.string.common_network_error))
                        else ->
                            state.copy(isLoading = false, isRefreshing = false)
                    }
                }
            }
        }
    }

    /** Insert movieffm rows after their matching gimymax rows */
    private fun interleaveRows(gimyRows: List<HomeRow>, ffmData: List<HomeRowData>): List<HomeRow> {
        val ffmByGimyId = ffmData.groupBy { ffmToGimyMap[it.typeId] }
        val result = mutableListOf<HomeRow>()
        for (row in gimyRows) {
            result.add(row)
            ffmByGimyId[row.typeId]?.forEach {
                result.add(HomeRow(it.title, it.typeId, it.items, it.sourceType))
            }
        }
        // Append any unmatched movieffm rows at the end
        val matchedGimyIds = gimyRows.map { it.typeId }.toSet()
        for ((gimyId, rows) in ffmByGimyId) {
            if (gimyId == null || gimyId !in matchedGimyIds) {
                rows.forEach { result.add(HomeRow(it.title, it.typeId, it.items, it.sourceType)) }
            }
        }
        return result
    }
}

/**
 * 「更多來源」單一列的狀態。
 *
 * 原本只有 List<Vod>：載入中與抓取失敗都是空陣列，那一列就整個從畫面上消失，
 * 使用者分不出「還在載」「這個來源沒有這個分類」「站台掛了」三件事，
 * 也沒有任何重試的出口。
 */
sealed interface MoreSourceRowState {
    data object Loading : MoreSourceRowState
    data class Loaded(val items: List<Vod>) : MoreSourceRowState
    data object Failed : MoreSourceRowState
}
