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
    val items: List<Vod> = emptyList(),
    val currentPage: Int = 1,
    val hasMore: Boolean = true,
    val title: String = "",
    val error: String? = null
)

private val categoryNames = mapOf(
    2 to "電視劇", 1 to "電影", 4 to "動漫", 29 to "綜藝",
    13 to "陸劇", 20 to "韓劇", 16 to "美劇", 21 to "日劇",
    14 to "台劇", 15 to "港劇", 30 to "紀錄片", 3 to "紀錄片"
)

@HiltViewModel
class BrowseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val vodRepository: VodRepository
) : ViewModel() {

    private val typeId: Int = savedStateHandle.get<String>("typeId")?.toIntOrNull() ?: 2

    private val _uiState = MutableStateFlow(BrowseUiState(
        title = categoryNames[typeId] ?: "瀏覽"
    ))
    val uiState: StateFlow<BrowseUiState> = _uiState.asStateFlow()

    init {
        loadPage(1)
    }

    fun loadPage(page: Int) {
        val isFirstPage = page == 1
        viewModelScope.launch {
            _uiState.update {
                if (isFirstPage) it.copy(isLoading = true, error = null)
                else it.copy(isLoadingMore = true, error = null)
            }
            try {
                val result = vodRepository.getVodList(SourceType.GIMYMAX, typeId, page)
                val newItems = result.items.distinctBy { it.id }
                _uiState.update { state ->
                    val merged = if (isFirstPage) newItems
                        else (state.items + newItems).distinctBy { it.id }
                    state.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        items = merged,
                        currentPage = page,
                        // If we got items, assume there could be more
                        hasMore = newItems.isNotEmpty()
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, isLoadingMore = false, error = e.message) }
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (!state.isLoading && !state.isLoadingMore && state.hasMore) {
            loadPage(state.currentPage + 1)
        }
    }
}
