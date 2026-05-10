package com.gimy.tv.ui.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gimy.tv.domain.model.*
import com.gimy.tv.domain.repository.VodRepository
import com.gimy.tv.domain.repository.WatchHistoryEntry
import com.gimy.tv.domain.repository.WatchHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
    /** Actual scraper used by the currently-playing line. Tracked so saveProgress can
     *  persist it for cross-source 「繼續觀看」 routing. Defaults to null until the first
     *  successful play, treated as "same as primary sourceType" downstream. */
    val playedSourceType: SourceType? = null,
    /** Currently-playing episode's kind (mirrors [Episode.kind]) — null = main.
     *  Persisted to watch history so resumed progress for "OAD 5" doesn't get
     *  conflated with regular ep5. */
    val episodeKind: String? = null,
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

                // Read watch progress early — used to decide whether enrichment is
                // needed up front (so 「繼續觀看」 can route back to a cross-source line)
                // and later for resumeMs.
                val progress = watchHistoryRepository.getProgress(id, sourceType)
                val historyOnDifferentSource = progress?.playedSourceType != null &&
                    progress.playedSourceType != sourceType

                // Primary may return zero playable lines (parser broke, page 404'd to a
                // templated "not found" body, …) — fall through to enrichment.
                // OR: history says the user's last play was on a cross-source enriched
                // line, which lives only in the enriched detail; fetch enriched up front
                // so 「繼續觀看」 lands on the right line instead of primary's first.
                val detail = if (primary.episodes.isEmpty() || historyOnDifferentSource) {
                    val msg = if (primary.episodes.isEmpty()) "主來源暫無線路，搜尋其他來源中…"
                        else "從上次的線路繼續，搜尋來源中…"
                    _uiState.update { it.copy(loadingMessage = msg) }
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
                val resumeMs = if (progress != null && firstEp != null &&
                    progress.episodeNum == firstEp.number &&
                    progress.sourceId == firstSource.sourceId
                ) progress.positionMs else 0L

                _uiState.update { it.copy(
                    vodTitle = detail.vod.title,
                    allSources = detail.episodes,
                ) }

                val err = playWithFallback(
                    ordered, initialEpisodeNum, resumeMs,
                    episodeKind = progress?.episodeKind,
                )
                if (err != null) {
                    _uiState.update { it.copy(
                        isLoading = false,
                        error = "所有線路均無法播放\n（最後錯誤: $err）"
                    ) }
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
     *
     * Returns null on success, or a short summary of the LAST failure when every
     * candidate failed — callers can surface that to the user / it also lands in
     * Logcat under tag "PlayerFallback" per-attempt so we can diagnose dead lines
     * without having to reproduce the issue.
     */
    private suspend fun playWithFallback(
        candidates: List<EpisodeGroup>,
        episodeNum: Int,
        resumeMs: Long = 0L,
        /** When set, prefer episodes whose kind matches; falls back to plain
         *  number match if no kind-tagged variant exists on a candidate line.
         *  Used by continue-play so a saved "OAD 5" history routes back to the
         *  OAD entry instead of grabbing the first ep with number=5. */
        episodeKind: String? = null,
    ): String? {
        var lastErr: String? = null
        for ((i, src) in candidates.withIndex()) {
            // Strict episode match. Previously we fell back to `firstOrNull()` when the
            // requested number didn't exist on this line — that silently took the user
            // from "第30集" to "第1集" without telling them. Now we skip the line and
            // surface "no line carries ep N" if every candidate misses.
            val ep = (if (episodeKind != null) {
                src.episodes.firstOrNull { it.number == episodeNum && it.kind == episodeKind }
            } else null) ?: src.episodes.firstOrNull { it.number == episodeNum }
            if (ep == null) {
                lastErr = "「${src.sourceName}」無第${episodeNum}集"
                android.util.Log.w("PlayerFallback",
                    "${src.sourceName}: missing ep$episodeNum (has ${src.episodes.size} eps)")
                continue
            }
            val msg = if (i == 0) "正在連接「${src.sourceName}」線路…"
                else "「${candidates[i - 1].sourceName}」無法播放，改用「${src.sourceName}」…"
            _uiState.update { it.copy(isLoading = true, error = null, loadingMessage = msg) }
            try {
                // Route through the ACTUAL scraper for this group. Cross-source enriched
                // groups carry their real sourceType; primary groups don't (legacy default
                // null) and fall back to the player's own sourceType. Without this fix,
                // every secondary line tried to decode its playUrl with the PRIMARY
                // scraper's logic, which would fail for any non-trivial scheme (e.g.
                // EnyTV's slug-based playUrl fed to GimyTV's regex). Suspected root cause
                // of the "all lines fail" reports for cross-source content.
                val effectiveSourceType = src.sourceType ?: sourceType
                // 8s per-line timeout — slow/stuck sources used to block the whole chain
                // for 30+ seconds (OkHttp default read timeout) before we moved on.
                val data = withTimeout(8_000) {
                    vodRepository.getPlayerData(effectiveSourceType, ep.playUrl)
                }
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
                        playedSourceType = effectiveSourceType,
                        episodeKind = ep.kind,
                    )
                }
                return null
            } catch (e: TimeoutCancellationException) {
                lastErr = "${src.sourceName}: 連線逾時（>8s）"
                android.util.Log.w("PlayerFallback",
                    "${src.sourceName} ep${ep.number} url=${ep.playUrl.take(120)} → TIMEOUT")
                continue
            } catch (e: Exception) {
                lastErr = "${src.sourceName}: ${e.javaClass.simpleName}${e.message?.let { ": $it" }.orEmpty()}"
                android.util.Log.w("PlayerFallback",
                    "${src.sourceName} ep${ep.number} url=${ep.playUrl.take(120)} → $lastErr")
                continue
            }
        }
        return lastErr ?: "no candidates"
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
                    durationMs = durationMs,
                    // Persist the actual scraper that played so future "繼續觀看"
                    // can route the user back to the same enriched line.
                    playedSourceType = state.playedSourceType,
                    episodeKind = state.episodeKind,
                )
            )
        }
    }

    fun switchEpisode(episodeNum: Int) {
        val detail = vodDetail ?: return
        val ordered = orderedSourcesFrom(detail.episodes, _uiState.value.sourceId)
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, loadingMessage = "正在載入第${episodeNum}集…") }
            val err = playWithFallback(ordered, episodeNum, resumeMs = 0L)
            if (err != null) {
                _uiState.update { it.copy(
                    isLoading = false,
                    error = "所有線路均無法播放第${episodeNum}集\n（最後錯誤: $err）"
                ) }
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
            val err = playWithFallback(ordered, episodeNum, resumeMs = 0L)
            if (err != null) {
                _uiState.update { it.copy(
                    isLoading = false,
                    error = "所有線路均無法播放\n（最後錯誤: $err）"
                ) }
            }
        }
    }

    /**
     * Auto-advance fired by PlayerScreen on STATE_ENDED. Single-video sources
     * (XNXX / Jable / 5278 — each vod is one video, episodes.size == 1) used to
     * trigger 「所有線路均無法播放第 2 集」 because we blindly called
     * switchEpisode(currentEp + 1) which doesn't exist anywhere. Guard by
     * confirming at least one line in the merged detail carries the next number.
     */
    fun nextEpisode() {
        val detail = vodDetail ?: return
        val nextNum = _uiState.value.episodeNum + 1
        val hasNext = detail.episodes.any { line -> line.episodes.any { it.number == nextNum } }
        if (!hasNext) return
        switchEpisode(nextNum)
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
