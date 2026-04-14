package com.gimy.tv.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gimy.tv.domain.model.*
import com.gimy.tv.domain.repository.FavoriteRepository
import com.gimy.tv.domain.repository.VodRepository
import com.gimy.tv.domain.repository.WatchHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailUiState(
    val isLoading: Boolean = true,
    val detail: VodDetail? = null,
    val isFavorite: Boolean = false,
    val lastEpisode: Int? = null,
    val lastSourceId: Int? = null,
    val error: String? = null
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val vodRepository: VodRepository,
    private val favoriteRepository: FavoriteRepository,
    private val watchHistoryRepository: WatchHistoryRepository
) : ViewModel() {

    private val sourceTypeName: String = savedStateHandle["sourceType"] ?: "GIMYMAX"
    private val vodId: Long? = savedStateHandle.get<String>("vodId")?.toLongOrNull()
    private val sourceType = runCatching { SourceType.valueOf(sourceTypeName) }.getOrDefault(SourceType.GIMYMAX)

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    init {
        loadDetail()
        observeFavorite()
        loadWatchProgress()
    }

    private fun loadDetail() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val id = vodId ?: run {
                _uiState.update { it.copy(isLoading = false, error = "無效的影片 ID") }
                return@launch
            }
            try {
                // Phase 1: Load primary source detail (fast — show immediately)
                val detail = vodRepository.getVodDetail(sourceType, id)
                _uiState.update { it.copy(isLoading = false, detail = detail) }

                // Phase 2: Enrich with cross-source data in background (non-blocking)
                try {
                    val enriched = vodRepository.getEnrichedVodDetail(sourceType, id, cachedPrimary = detail)
                    _uiState.update { it.copy(detail = enriched) }
                } catch (_: Exception) {
                    // Enrichment failed silently — primary detail is already shown
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    private fun observeFavorite() {
        val id = vodId ?: return
        viewModelScope.launch {
            favoriteRepository.isFavorite(id, sourceType).collect { fav ->
                _uiState.update { it.copy(isFavorite = fav) }
            }
        }
    }

    private fun loadWatchProgress() {
        val id = vodId ?: return
        viewModelScope.launch {
            val progress = watchHistoryRepository.getProgress(id, sourceType)
            if (progress != null) {
                _uiState.update {
                    it.copy(lastEpisode = progress.episodeNum, lastSourceId = progress.sourceId)
                }
            }
        }
    }

    fun deleteHistory() {
        val id = vodId ?: return
        viewModelScope.launch {
            watchHistoryRepository.deleteEntry(id, sourceType)
            _uiState.update { it.copy(lastEpisode = null, lastSourceId = null) }
        }
    }

    fun toggleFavorite() {
        val id = vodId ?: return
        val detail = _uiState.value.detail ?: return
        viewModelScope.launch {
            if (_uiState.value.isFavorite) {
                favoriteRepository.removeFavorite(id, sourceType)
            } else {
                favoriteRepository.addFavorite(detail.vod)
            }
        }
    }
}
