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
                val primary = vodRepository.getVodDetail(sourceType, id)
                vodDetail = primary

                // Primary may return zero playable lines (parser broke, page 404'd to a
                // templated "not found" body, the thread no longer embeds a player, …).
                // DetailScreen's Phase 2 enrichment usually has those — without folding it
                // in here, we'd surface "此影片暫無可用播放線路" while the detail page just
                // showed the user that other lines exist. Fall through to enrichment first.
                val detail = if (primary.episodes.isEmpty()) {
                    _uiState.update { it.copy(loadingMessage = "主來源暫無線路，搜尋其他來源中…") }
                    val enriched = try {
                        vodRepository.getEnrichedVodDetail(sourceType, id, cachedPrimary = primary)
                    } catch (_: Exception) { primary }
                    vodDetail = enriched
                    if (enriched.episodes.isEmpty()) {
                        _uiState.update { it.copy(isLoading = false, error = "此影片暫無可用播放線路") }
                        return@launch
                    }
                    enriched
                } else {
                    primary
                }

                // Order: requested source first, then remaining sources as fallbacks.
                val ordered = orderedSourcesFrom(detail.episodes, initialSourceId)
                val firstSource = ordered.first()
                val firstEp = firstSource.episodes.find { it.number == initialEpisodeNum }
                    ?: firstSource.episodes.firstOrNull()

                // Resume position only honored when we land on the exact (source, episode)
                // pair the user came from. Fallback sources or different episodes restart at 0.
                val progress = watchHistoryRepository.getProgress(id, sourceType)
                val resumeMs = if (progress != null && firstEp != null &&
                    progress.episodeNum == firstEp.number &&
                    progress.sourceId == firstSource.sourceId
                ) progress.positionMs else 0L

                _uiState.update { it.copy(
                    vodTitle = detail.vod.title,
                    allSources = detail.episodes,
                ) }

                val ok = playWithFallback(ordered, initialEpisodeNum, resumeMs)
                if (!ok) {
                    _uiState.update { it.copy(isLoading = false, error = "所有線路均無法播放") }
                    return@launch
                }
                enrichWithCrossSource()
            } catch (e: java.io.IOException) {
                _uiState.update { it.copy(isLoading = false, error = "網路連線失敗，請檢查網路後重試") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "播放失敗: ${e.message}") }
            }
        }
    }

    /**
     * Try playing `episodeNum` across `candidates` sequentially. First successful
     * URL resolution commits the stream + state; on every failure we move to the
     * next candidate with a "「X」失敗，改用「Y」…" hint so users see what's happening.
     * Returns true on success, false when every line failed.
     *
     * Used by initial load + episode/source switches so a dead line never lands the
     * user at a static error screen — we walk down the rank automatically.
     */
    private suspend fun playWithFallback(
        candidates: List<EpisodeGroup>,
        episodeNum: Int,
        resumeMs: Long = 0L,
    ): Boolean {
        for ((i, src) in candidates.withIndex()) {
            val ep = src.episodes.find { it.number == episodeNum }
                ?: src.episodes.firstOrNull()
                ?: continue
            val msg = if (i == 0) "正在連接「${src.sourceName}」線路…"
                else "「${candidates[i - 1].sourceName}」無法播放，改用「${src.sourceName}」…"
            _uiState.update { it.copy(isLoading = true, error = null, loadingMessage = msg) }
            try {
                val data = vodRepository.getPlayerData(sourceType, ep.playUrl)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = null,
                        streamUrl = data.streamUrl,
                        episodeNum = ep.number,
                        episodeTitle = ep.title,
                        sourceId = src.sourceId,
                        sourceName = src.sourceName,
                        totalEpisodes = src.episodes.size,
                        resumePositionMs = if (i == 0) resumeMs else 0L,
                    )
                }
                return true
            } catch (_: Exception) {
                continue
            }
        }
        return false
    }

    /** Reorder so [primarySourceId] is first; the rest keep their original (ranked) order. */
    private fun orderedSourcesFrom(
        all: List<EpisodeGroup>,
        primarySourceId: Int,
    ): List<EpisodeGroup> {
        if (all.isEmpty()) return all
        val primaryIdx = all.indexOfFirst { it.sourceId == primarySourceId }
        return if (primaryIdx <= 0) all
        else listOf(all[primaryIdx]) + all.filterIndexed { i, _ -> i != primaryIdx }
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
        val ordered = orderedSourcesFrom(detail.episodes, _uiState.value.sourceId)
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, loadingMessage = "正在載入第${episodeNum}集…") }
            val ok = playWithFallback(ordered, episodeNum, resumeMs = 0L)
            if (!ok) {
                _uiState.update { it.copy(isLoading = false, error = "所有線路均無法播放第${episodeNum}集") }
            }
        }
    }

    fun switchSource(sourceId: Int) {
        val detail = vodDetail ?: return
        val ordered = orderedSourcesFrom(detail.episodes, sourceId)
        if (ordered.isEmpty()) return
        val target = ordered.first()
        val episodeNum = _uiState.value.episodeNum
        viewModelScope.launch {
            _uiState.update { it.copy(
                isLoading = true,
                error = null,
                loadingMessage = "正在切換至「${target.sourceName}」…",
            ) }
            val ok = playWithFallback(ordered, episodeNum, resumeMs = 0L)
            if (!ok) {
                _uiState.update { it.copy(isLoading = false, error = "所有線路均無法播放") }
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
