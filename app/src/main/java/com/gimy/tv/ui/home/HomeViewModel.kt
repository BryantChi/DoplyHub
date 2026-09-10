package com.gimy.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    private val cacheCleaner: com.gimy.tv.data.cache.CacheCleaner,
    sourcePreferencesRepository: com.gimy.tv.data.preferences.SourcePreferencesRepository,
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
    private val moreSourceCache = mutableMapOf<String, MutableStateFlow<List<Vod>>>()

    fun moreSourceRow(sourceType: SourceType, typeId: Int): StateFlow<List<Vod>> {
        val key = "${sourceType.name}_$typeId"
        return moreSourceCache.getOrPut(key) {
            MutableStateFlow<List<Vod>>(emptyList()).also { flow ->
                viewModelScope.launch {
                    try {
                        flow.value = vodRepository.getVodList(sourceType, typeId, 1).items.take(15)
                    } catch (_: Exception) {
                        flow.value = emptyList()
                    }
                }
            }
        }
    }

    // Movieffm typeId → matching gimymax typeId for interleaving
    private val ffmToGimyMap = mapOf(
        101 to 1, 201 to 20, 202 to 13, 203 to 16, 204 to 21,
        205 to 4, 207 to 14, 208 to 15, 206 to 29
    )

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
        moreSourceCache.clear()  // force "More Sources" rows to re-fetch on next collect
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
        moreSourceCache.clear()
        viewModelScope.launch {
            // 先進載入中，清快取這段也要有畫面回饋，否則按下去像是沒反應。
            _uiState.update { it.copy(isLoading = true, error = null) }
            // 等重新探測跑完再抓：只丟背景刷新的話當次仍用舊網址，使用者得按第二次才生效。
            runCatching { cacheCleaner.clearAll() }
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
        viewModelScope.launch {
            _uiState.update {
                if (isRefresh) it.copy(isRefreshing = true, error = null)
                else it.copy(isLoading = true, error = null)
            }
            try {
                val gimyRows = vodRepository.getGimyHomeRows(forceRefresh = force)
                    .filter { it.items.isNotEmpty() }
                    .map { HomeRow(it.title, it.typeId, it.items, it.sourceType) }

                if (gimyRows.isNotEmpty()) {
                    _uiState.update { it.copy(isLoading = false, rows = gimyRows) }
                } else if (!isRefresh) {
                    // Phase 1 returned nothing — keep loading, let Phase 2 try
                    _uiState.update { it.copy(rows = emptyList()) }
                }
                // On refresh: leave previous rows untouched until Phase 2 reports
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
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

                _uiState.update { state ->
                    if (ffmRows.isNotEmpty()) {
                        val merged = interleaveRows(state.rows, ffmRows)
                        state.copy(isLoading = false, isRefreshing = false, rows = merged)
                    } else if (state.rows.isEmpty()) {
                        if (isRefresh && previousRows.isNotEmpty()) {
                            state.copy(isLoading = false, isRefreshing = false, rows = previousRows)
                        } else {
                            state.copy(isLoading = false, isRefreshing = false, error = "無法載入內容，請檢查網路連線")
                        }
                    } else {
                        state.copy(isLoading = false, isRefreshing = false)
                    }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update { state ->
                    when {
                        isRefresh && state.rows.isEmpty() && previousRows.isNotEmpty() ->
                            state.copy(isLoading = false, isRefreshing = false, rows = previousRows)
                        state.rows.isEmpty() && state.isLoading ->
                            state.copy(isLoading = false, isRefreshing = false, error = "無法載入內容，請檢查網路連線")
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
