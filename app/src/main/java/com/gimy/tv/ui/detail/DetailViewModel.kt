package com.gimy.tv.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gimy.tv.domain.model.*
import com.gimy.tv.domain.repository.FavoriteRepository
import com.gimy.tv.domain.repository.VodRepository
import com.gimy.tv.domain.repository.WatchHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    /** Phase 2 in flight — primary detail is on screen but cross-source enrichment
     *  (additional sources/episode lists/series) is still being fetched. Drives the
     *  inline "更多來源載入中" hint. */
    val isEnriching: Boolean = false,
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

    private val sourceTypeName: String = savedStateHandle["sourceType"] ?: "GIMYTV"
    private val vodId: Long? = savedStateHandle.get<String>("vodId")?.toLongOrNull()
    private val sourceType = runCatching { SourceType.valueOf(sourceTypeName) }.getOrDefault(SourceType.GIMYTV)

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    /** Tracks the in-flight loadDetail coroutine. refresh() cancels it before kicking
     *  off a new one — without this, an old Phase 2 enrichment that finishes after a
     *  refresh started can race-overwrite the fresh state. */
    private var loadJob: Job? = null

    init {
        loadDetail(isRefresh = false)
        observeFavorite()
        loadWatchProgress()
    }

    fun refresh() {
        if (vodId == null || _uiState.value.isRefreshing) return
        loadDetail(isRefresh = true)
    }

    private fun loadDetail(isRefresh: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update {
                if (isRefresh) it.copy(isRefreshing = true, error = null)
                else it.copy(isLoading = true, error = null)
            }
            val id = vodId ?: run {
                _uiState.update {
                    if (isRefresh) it.copy(isRefreshing = false, error = "無效的影片 ID")
                    else it.copy(isLoading = false, error = "無效的影片 ID")
                }
                return@launch
            }
            try {
                // Phase 1: Load primary source detail (fast — show immediately).
                val detail = vodRepository.getVodDetail(sourceType, id)
                if (!isActive) return@launch  // refresh() cancelled us mid-fetch
                _uiState.update {
                    it.copy(isLoading = false, isRefreshing = false, isEnriching = true, detail = detail)
                }

                // Phase 1.5: search for series items (fast, independent child launch).
                launch {
                    try {
                        val seriesVods = vodRepository.searchSeriesVods(detail.vod)
                        if (!isActive) return@launch
                        if (seriesVods.isNotEmpty()) {
                            _uiState.update { state ->
                                val current = state.detail ?: return@update state
                                val seriesIds = seriesVods.map { it.id }.toSet()
                                state.copy(detail = current.copy(
                                    seriesVods = seriesVods,
                                    relatedVods = current.relatedVods.filter { it.id !in seriesIds }
                                ))
                            }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { }
                }

                // Phase 2: enrich with cross-source data (non-blocking).
                try {
                    val enriched = vodRepository.getEnrichedVodDetail(
                        sourceType, id, cachedPrimary = detail, forceRefresh = isRefresh,
                    )
                    if (!isActive) return@launch
                    _uiState.update { state ->
                        // Preserve series from Phase 1.5 if enriched doesn't have any.
                        val currentSeries = state.detail?.seriesVods ?: emptyList()
                        val finalSeries = if (enriched.seriesVods.isNotEmpty()) enriched.seriesVods else currentSeries
                        val seriesIds = finalSeries.map { it.id }.toSet()
                        state.copy(detail = enriched.copy(
                            seriesVods = finalSeries,
                            relatedVods = enriched.relatedVods.filter { it.id !in seriesIds }
                        ))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Enrichment failed silently — primary detail is already shown.
                } finally {
                    // Skip when cancelled — a fresh load is in flight and we shouldn't
                    // race-clobber its isEnriching state.
                    if (isActive) _uiState.update { it.copy(isEnriching = false) }
                }
            } catch (e: CancellationException) {
                // A newer loadDetail() cancelled us — leave state alone for the new load.
                throw e
            } catch (e: java.io.IOException) {
                // On refresh: keep existing detail visible, drop the spinner silently.
                _uiState.update {
                    if (isRefresh) it.copy(isRefreshing = false, isEnriching = false)
                    else it.copy(isLoading = false, isEnriching = false, error = "網路連線失敗，請檢查網路後重試")
                }
            } catch (e: Exception) {
                _uiState.update {
                    if (isRefresh) it.copy(isRefreshing = false, isEnriching = false)
                    else it.copy(isLoading = false, isEnriching = false, error = "載入失敗: ${e.message}")
                }
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
