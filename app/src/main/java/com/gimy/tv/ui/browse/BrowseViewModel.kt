package com.gimy.tv.ui.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.domain.repository.VodRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BrowseUiState(
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val items: List<Vod> = emptyList(),
    val currentPage: Int = 1,
    val hasMore: Boolean = true,
    val title: String = "",
    val error: String? = null
)

private val categoryNames = mapOf(
    2 to "電視劇", 1 to "電影", 4 to "動漫", 29 to "綜藝",
    13 to "陸劇", 20 to "韓劇", 16 to "美劇", 21 to "日劇",
    14 to "台劇", 15 to "港劇", 30 to "紀錄片", 3 to "紀錄片",
    100 to "電影", 101 to "熱門電影", 200 to "電視劇",
    201 to "韓劇", 202 to "陸劇", 203 to "美劇", 204 to "日劇",
    205 to "動漫", 206 to "綜藝", 207 to "台劇", 208 to "港劇",
)

@HiltViewModel
class BrowseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val vodRepository: VodRepository
) : ViewModel() {

    private val sourceTypeName: String = savedStateHandle["sourceType"] ?: "GIMYTV"
    private val sourceType = runCatching { SourceType.valueOf(sourceTypeName) }.getOrDefault(SourceType.GIMYTV)
    private val typeId: Int = savedStateHandle.get<String>("typeId")?.toIntOrNull() ?: 2

    private val _uiState = MutableStateFlow(BrowseUiState(
        title = categoryNames[typeId] ?: "瀏覽"
    ))
    val uiState: StateFlow<BrowseUiState> = _uiState.asStateFlow()

    init {
        loadPage(1)
    }

    fun loadPage(page: Int) {
        loadPageInternal(page, isRefresh = false)
    }

    fun refresh() {
        if (_uiState.value.isRefreshing) return
        loadPageInternal(1, isRefresh = true)
    }

    private fun loadPageInternal(page: Int, isRefresh: Boolean) {
        val isFirstPage = page == 1
        viewModelScope.launch {
            _uiState.update {
                when {
                    isRefresh -> it.copy(isRefreshing = true, error = null)
                    isFirstPage -> it.copy(isLoading = true, error = null)
                    else -> it.copy(isLoadingMore = true, error = null)
                }
            }
            try {
                val result = vodRepository.getVodList(sourceType, typeId, page)
                // 分類頁原本完全沒有記錄，空白時分不出是抓不到、解析 0 筆、還是站方真的沒內容。
                // 「HTTP 200 + 解析 0 筆」是這個專案最常見也最難察覺的失效（站方改版、換模板
                // 都長這樣），筆數是唯一能一眼分辨的線索。
                android.util.Log.w(
                    "BrowseLoad",
                    "$sourceType(typeId=$typeId) page=$page → ${result.items.size} 筆, hasMore=${result.hasMore}",
                )
                val newItems = result.items.distinctBy { "${it.sourceType}_${it.id}" }
                _uiState.update { state ->
                    val merged = if (isFirstPage) newItems
                        else (state.items + newItems).distinctBy { "${it.sourceType}_${it.id}" }
                    // Refresh keeps previous items visible if backend returned nothing
                    val finalItems = if (isRefresh && newItems.isEmpty()) state.items else merged
                    state.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        isRefreshing = false,
                        items = finalItems,
                        currentPage = if (isRefresh && newItems.isEmpty()) state.currentPage else page,
                        hasMore = newItems.isNotEmpty() || (isRefresh && state.items.isNotEmpty())
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w("BrowseLoad", "$sourceType(typeId=$typeId) page=$page 失敗", e)
                _uiState.update {
                    if (isRefresh) it.copy(isRefreshing = false, error = e.message)
                    else it.copy(isLoading = false, isLoadingMore = false, error = e.message)
                }
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (!state.isLoading && !state.isLoadingMore && !state.isRefreshing && state.hasMore) {
            loadPageInternal(state.currentPage + 1, isRefresh = false)
        }
    }
}
