package com.gimy.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gimy.tv.domain.model.*
import com.gimy.tv.domain.repository.VodRepository
import com.gimy.tv.domain.repository.WatchHistoryEntry
import com.gimy.tv.domain.repository.WatchHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeRow(
    val title: String,
    val typeId: Int,
    val items: List<Vod>
)

data class HomeUiState(
    val isLoading: Boolean = true,
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
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val source = SourceType.GIMYMAX
                val categories = listOf(
                    20 to "韓劇",
                    13 to "陸劇",
                    16 to "美劇",
                    21 to "日劇",
                    1 to "電影",
                    4 to "動漫",
                    14 to "台劇",
                    15 to "港劇",
                    29 to "綜藝",
                    30 to "紀錄片",
                )

                val deferredRows = categories.map { (typeId, name) ->
                    viewModelScope.async {
                        try {
                            val result = vodRepository.getVodList(source, typeId, 1)
                            HomeRow(title = name, typeId = typeId, items = result.items.take(15))
                        } catch (e: Exception) {
                            HomeRow(title = name, typeId = typeId, items = emptyList())
                        }
                    }
                }

                val rows = deferredRows.mapNotNull { deferred ->
                    val row = deferred.await()
                    if (row.items.isNotEmpty()) row else null
                }

                _uiState.update { it.copy(isLoading = false, rows = rows) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }
}
