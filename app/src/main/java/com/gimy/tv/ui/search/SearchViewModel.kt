package com.gimy.tv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.domain.repository.SearchHistoryRepository
import com.gimy.tv.domain.repository.VodRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val results: List<Vod> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val error: String? = null,
    val currentPage: Int = 1,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val vodRepository: VodRepository,
    private val searchHistoryRepository: SearchHistoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            searchHistoryRepository.getRecentSearches(10).collect { keywords ->
                _uiState.update { it.copy(recentSearches = keywords) }
            }
        }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    fun search(query: String = _uiState.value.query) {
        val q = query.trim()
        if (q.isBlank()) return
        _uiState.update { it.copy(query = q) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            searchHistoryRepository.addSearch(q)
            _uiState.update { it.copy(isSearching = true, error = null, hasSearched = true, results = emptyList(), currentPage = 1, hasMore = false) }
            try {
                val result = vodRepository.searchAllSources(q, 1)
                val unique = result.items.distinctBy { "${it.sourceType}_${it.id}" }
                _uiState.update { it.copy(isSearching = false, results = unique, currentPage = 1, hasMore = result.hasMore) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSearching = false, error = e.message, results = emptyList()) }
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isSearching || state.isLoadingMore || !state.hasMore || state.query.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            try {
                val nextPage = state.currentPage + 1
                val result = vodRepository.searchAllSources(state.query, nextPage)
                val combined = (state.results + result.items).distinctBy { "${it.sourceType}_${it.id}" }
                val hasNew = combined.size > state.results.size
                _uiState.update { it.copy(isLoadingMore = false, results = combined, currentPage = nextPage, hasMore = result.hasMore && hasNew) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoadingMore = false, hasMore = false) }
            }
        }
    }

    fun clearResults() {
        searchJob?.cancel()
        _uiState.update { it.copy(results = emptyList(), hasSearched = false, error = null, currentPage = 1, hasMore = false, isLoadingMore = false) }
    }
}
