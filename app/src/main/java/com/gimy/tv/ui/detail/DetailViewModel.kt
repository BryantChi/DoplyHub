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
    private val vodId: Long = savedStateHandle.get<String>("vodId")?.toLongOrNull() ?: 0L
    private val sourceType = SourceType.valueOf(sourceTypeName)

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
            try {
                val detail = vodRepository.getVodDetail(sourceType, vodId)
                _uiState.update { it.copy(isLoading = false, detail = detail) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    private fun observeFavorite() {
        viewModelScope.launch {
            favoriteRepository.isFavorite(vodId, sourceType).collect { fav ->
                _uiState.update { it.copy(isFavorite = fav) }
            }
        }
    }

    private fun loadWatchProgress() {
        viewModelScope.launch {
            val progress = watchHistoryRepository.getProgress(vodId, sourceType)
            if (progress != null) {
                _uiState.update {
                    it.copy(lastEpisode = progress.episodeNum, lastSourceId = progress.sourceId)
                }
            }
        }
    }

    fun deleteHistory() {
        viewModelScope.launch {
            watchHistoryRepository.deleteEntry(vodId, sourceType)
            _uiState.update { it.copy(lastEpisode = null, lastSourceId = null) }
        }
    }

    fun toggleFavorite() {
        val detail = _uiState.value.detail ?: return
        viewModelScope.launch {
            if (_uiState.value.isFavorite) {
                favoriteRepository.removeFavorite(vodId, sourceType)
            } else {
                favoriteRepository.addFavorite(detail.vod)
            }
        }
    }
}
