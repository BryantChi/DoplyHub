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
    val sourceType: SourceType = SourceType.GIMYMAX
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
    private val watchHistoryRepository: WatchHistoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

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
        fetchHome(isRefresh = false)
    }

    fun refresh() {
        if (_uiState.value.isRefreshing) return
        fetchHome(isRefresh = true)
    }

    private fun fetchHome(isRefresh: Boolean) {
        // Snapshot so we can preserve visible content if refresh produces nothing
        val previousRows = _uiState.value.rows

        // Phase 1: Load gimymax (fast — show immediately)
        viewModelScope.launch {
            _uiState.update {
                if (isRefresh) it.copy(isRefreshing = true, error = null)
                else it.copy(isLoading = true, error = null)
            }
            try {
                val gimyRows = vodRepository.getGimyHomeRows(forceRefresh = isRefresh)
                    .filter { it.items.isNotEmpty() }
                    .map { HomeRow(it.title, it.typeId, it.items, it.sourceType) }

                if (gimyRows.isNotEmpty()) {
                    _uiState.update { it.copy(isLoading = false, rows = gimyRows) }
                } else if (!isRefresh) {
                    // GimyMax returned nothing — keep loading, let Phase 2 try
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
                val ffmRows = vodRepository.getMovieffmHomeRows(forceRefresh = isRefresh)
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
