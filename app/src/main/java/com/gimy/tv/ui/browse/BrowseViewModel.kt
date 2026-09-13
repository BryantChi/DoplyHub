package com.gimy.tv.ui.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gimy.tv.domain.model.displayName
import com.gimy.tv.domain.model.categoryMap
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

/**
 * 這個來源的這個 typeId 該叫什麼。
 *
 * 名稱一律走該來源自己的 [categoryMap]，不再另外維護一張 typeId→名稱的扁平表。
 * 扁平表在多來源下必然會錯：gimy 的 14 是台劇、gimy.tw 的 14 卻是港劇，
 * 而 kubo 的韓劇是 24（扁平表裡根本沒有，標題會 fallback 成「瀏覽」）。
 * 2026-09-13 的「點日劇顯示港劇」就是同一個病。
 */
internal fun browseTitleFor(sourceType: SourceType, typeId: Int): String =
    sourceType.categoryMap.categoryFor(typeId)?.displayName ?: "瀏覽"

@HiltViewModel
class BrowseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val vodRepository: VodRepository
) : ViewModel() {

    private val sourceTypeName: String = savedStateHandle["sourceType"] ?: "GIMYTV"
    private val sourceType = runCatching { SourceType.valueOf(sourceTypeName) }.getOrDefault(SourceType.GIMYTV)
    private val typeId: Int = savedStateHandle.get<String>("typeId")?.toIntOrNull() ?: 2

    private val _uiState = MutableStateFlow(BrowseUiState(
        title = browseTitleFor(sourceType, typeId)
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
