package com.gimy.tv.ui.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gimy.tv.domain.model.*
import com.gimy.tv.domain.repository.VodRepository
import com.gimy.tv.domain.repository.WatchHistoryEntry
import com.gimy.tv.domain.repository.WatchHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerUiState(
    val isLoading: Boolean = true,
    val loadingMessage: String = "正在載入播放資訊…",
    val streamUrl: String? = null,
    val vodTitle: String = "",
    val episodeTitle: String = "",
    val episodeNum: Int = 1,
    val sourceId: Int = 1,
    val sourceName: String = "",
    val resumePositionMs: Long = 0L,
    val allSources: List<EpisodeGroup> = emptyList(),
    val totalEpisodes: Int = 0,
    val error: String? = null,
    val isFullscreen: Boolean = false,
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val vodRepository: VodRepository,
    private val watchHistoryRepository: WatchHistoryRepository
) : ViewModel() {

    private val sourceTypeName: String = savedStateHandle["sourceType"] ?: "GIMYTV"
    val vodId: Long? = savedStateHandle.get<String>("vodId")?.toLongOrNull()
    private val initialSourceId: Int = savedStateHandle.get<String>("sourceId")?.toIntOrNull() ?: 0
    private val initialEpisodeNum: Int = savedStateHandle.get<String>("episodeNum")?.toIntOrNull() ?: 1
    val sourceType = runCatching { SourceType.valueOf(sourceTypeName) }.getOrDefault(SourceType.GIMYTV)

    private val _uiState = MutableStateFlow(PlayerUiState(
        episodeNum = initialEpisodeNum,
        sourceId = initialSourceId
    ))
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var vodDetail: VodDetail? = null

    init {
        loadPlayer()
    }

    private fun loadPlayer() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, loadingMessage = "正在載入影片資訊…") }
            val id = vodId ?: run {
                _uiState.update { it.copy(isLoading = false, error = "無效的影片 ID") }
                return@launch
            }
            try {
                // Load primary detail first for fast playback start
                val detail = vodRepository.getVodDetail(sourceType, id)
                vodDetail = detail

                if (detail.episodes.isEmpty()) {
                    _uiState.update { it.copy(isLoading = false, error = "此影片暫無可用播放線路") }
                    return@launch
                }

                // Pick source: use requested sourceId, or first (already sorted by stability)
                val sourceGroup = if (initialSourceId > 0) {
                    detail.episodes.find { it.sourceId == initialSourceId } ?: detail.episodes.first()
                } else {
                    detail.episodes.first() // first = most stable due to sorting
                }

                val episode = sourceGroup.episodes.find { it.number == initialEpisodeNum }
                    ?: sourceGroup.episodes.firstOrNull()

                if (episode == null) {
                    _uiState.update { it.copy(isLoading = false, error = "找不到第${initialEpisodeNum}集") }
                    return@launch
                }

                _uiState.update { it.copy(
                    loadingMessage = "正在連接「${sourceGroup.sourceName}」線路…",
                    allSources = detail.episodes,
                    vodTitle = detail.vod.title,
                    sourceName = sourceGroup.sourceName
                ) }

                // Get stream URL
                val playerData = vodRepository.getPlayerData(sourceType, episode.playUrl)

                // Check resume position
                val progress = watchHistoryRepository.getProgress(id, sourceType)
                val resumeMs = if (progress != null &&
                    progress.episodeNum == episode.number &&
                    progress.sourceId == sourceGroup.sourceId
                ) progress.positionMs else 0L

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        streamUrl = playerData.streamUrl,
                        vodTitle = detail.vod.title,
                        episodeTitle = episode.title,
                        episodeNum = episode.number,
                        sourceId = sourceGroup.sourceId,
                        sourceName = sourceGroup.sourceName,
                        resumePositionMs = resumeMs,
                        totalEpisodes = sourceGroup.episodes.size
                    )
                }

                // Background: enrich with cross-source routes for fallback
                enrichWithCrossSource()
            } catch (e: Exception) {
                // If current source fails, try next one
                val detail = vodDetail
                if (detail != null && detail.episodes.size > 1) {
                    val currentIdx = detail.episodes.indexOfFirst { it.sourceId == _uiState.value.sourceId }
                    val nextIdx = if (currentIdx >= 0 && currentIdx < detail.episodes.size - 1) currentIdx + 1 else -1
                    if (nextIdx >= 0) {
                        val nextSource = detail.episodes[nextIdx]
                        _uiState.update { it.copy(
                            loadingMessage = "「${_uiState.value.sourceName}」失敗，自動嘗試「${nextSource.sourceName}」…"
                        ) }
                        tryAlternateSource(nextSource)
                        return@launch
                    }
                }
                val msg = if (e is java.io.IOException) "網路連線失敗，請檢查網路後重試" else "播放失敗: ${e.message}"
                _uiState.update { it.copy(isLoading = false, error = msg) }
            }
        }
    }

    private fun tryAlternateSource(sourceGroup: EpisodeGroup) {
        viewModelScope.launch {
            try {
                val episode = sourceGroup.episodes.find { it.number == _uiState.value.episodeNum }
                    ?: sourceGroup.episodes.firstOrNull()
                    ?: throw Exception("No episodes")

                val playerData = vodRepository.getPlayerData(sourceType, episode.playUrl)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        streamUrl = playerData.streamUrl,
                        episodeTitle = episode.title,
                        episodeNum = episode.number,
                        sourceId = sourceGroup.sourceId,
                        sourceName = sourceGroup.sourceName,
                        totalEpisodes = sourceGroup.episodes.size
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "所有線路均無法播放") }
            }
        }
    }

    fun saveProgress(positionMs: Long, durationMs: Long) {
        val id = vodId ?: return
        val state = _uiState.value
        if (positionMs <= 0) return
        // Keep resumePositionMs in sync so rotation uses the latest value
        _uiState.update { it.copy(resumePositionMs = positionMs) }
        viewModelScope.launch {
            watchHistoryRepository.saveProgress(
                WatchHistoryEntry(
                    vodId = id,
                    sourceType = sourceType,
                    title = state.vodTitle,
                    coverUrl = vodDetail?.vod?.coverUrl ?: "",
                    episodeNum = state.episodeNum,
                    episodeTitle = state.episodeTitle,
                    sourceId = state.sourceId,
                    positionMs = positionMs,
                    durationMs = durationMs
                )
            )
        }
    }

    fun switchEpisode(episodeNum: Int) {
        val detail = vodDetail ?: return
        val sourceGroup = detail.episodes.find { it.sourceId == _uiState.value.sourceId } ?: return
        val episode = sourceGroup.episodes.find { it.number == episodeNum } ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingMessage = "正在載入第${episodeNum}集…") }
            try {
                val playerData = vodRepository.getPlayerData(sourceType, episode.playUrl)
                _uiState.update {
                    it.copy(
                        isLoading = false, streamUrl = playerData.streamUrl,
                        episodeNum = episode.number, episodeTitle = episode.title, resumePositionMs = 0L
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "載入失敗: ${e.message}") }
            }
        }
    }

    fun switchSource(sourceId: Int) {
        val detail = vodDetail ?: return
        val sourceGroup = detail.episodes.find { it.sourceId == sourceId } ?: return
        val episode = sourceGroup.episodes.find { it.number == _uiState.value.episodeNum }
            ?: sourceGroup.episodes.firstOrNull() ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(
                isLoading = true,
                loadingMessage = "正在切換至「${sourceGroup.sourceName}」…"
            ) }
            try {
                val playerData = vodRepository.getPlayerData(sourceType, episode.playUrl)
                _uiState.update {
                    it.copy(
                        isLoading = false, streamUrl = playerData.streamUrl,
                        sourceId = sourceId, sourceName = sourceGroup.sourceName,
                        episodeNum = episode.number, episodeTitle = episode.title,
                        totalEpisodes = sourceGroup.episodes.size
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "線路切換失敗: ${e.message}") }
            }
        }
    }

    fun nextEpisode() {
        switchEpisode(_uiState.value.episodeNum + 1)
    }

    fun retryWithNextSource() {
        val detail = vodDetail ?: return
        val currentIdx = detail.episodes.indexOfFirst { it.sourceId == _uiState.value.sourceId }
        val nextIdx = if (currentIdx >= 0 && currentIdx < detail.episodes.size - 1) currentIdx + 1 else 0
        if (nextIdx < detail.episodes.size) {
            switchSource(detail.episodes[nextIdx].sourceId)
        }
    }

    private fun enrichWithCrossSource() {
        viewModelScope.launch {
            try {
                val id = vodId ?: return@launch
                val enriched = vodRepository.getEnrichedVodDetail(sourceType, id, cachedPrimary = vodDetail)
                vodDetail = enriched
                _uiState.update { it.copy(allSources = enriched.episodes) }
            } catch (_: Exception) {
                // Silent — primary routes already available
            }
        }
    }

    fun toggleFullscreen() {
        _uiState.update { it.copy(isFullscreen = !it.isFullscreen) }
    }

    fun setFullscreen(fullscreen: Boolean) {
        _uiState.update { it.copy(isFullscreen = fullscreen) }
    }
}
