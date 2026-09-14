package com.gimy.tv.ui.player

import android.content.Context
import com.gimy.tv.R
import dagger.hilt.android.qualifiers.ApplicationContext

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gimy.tv.domain.model.*
import com.gimy.tv.domain.repository.VodRepository
import com.gimy.tv.domain.repository.WatchHistoryEntry
import com.gimy.tv.domain.repository.WatchHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

data class PlayerUiState(
    val isLoading: Boolean = true,
    /** 空字串代表「還沒有更具體的訊息」，畫面會退回 player_loading_playback。
     *  這裡不能直接放文案：data class 的預設值取不到 Context。 */
    val loadingMessage: String = "",
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

/**
 * 每條線路的取流預算。
 *
 * 不只是外層 withTimeout 的參數，同一個值會一路傳到 OkHttp 的 call 上：
 * coroutine 的 withTimeout 中斷不了阻塞的 execute()，只包一層的話實際等的是
 * 網路層 20 秒的絕對上限。
 */
private const val PLAY_BUDGET_MS = 8_000L

@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val vodRepository: VodRepository,
    private val watchHistoryRepository: WatchHistoryRepository,
    /**
     * 交給 PlayerScreen 建 ExoPlayer 用，讓播放走 App 自己的 OkHttp 而非系統的
     * HttpURLConnection——後者不吃 App 的 DNS 設定，播放用的 CDN 一旦被 ISP 的 DNS 過濾
     * 就完全沒轍（實測「極速雲」的 v2.ppqrrs.com 被導向封鎖頁，模擬器正常、實機全黑）。
     *
     * 放在 ViewModel 而不是 Screen 直接取，是因為 Composable 拿 Hilt 相依要多繞
     * EntryPoint；ViewModel 本來就是這個畫面的相依來源。
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val mediaDataSourceFactory: androidx.media3.datasource.DataSource.Factory,
    @ApplicationContext private val context: Context,
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

    /** 在**播放**階段（而非取流階段）失敗過的線路 sourceId。
     *
     *  為什麼需要分開記：getPlayerData 成功只代表「網址拿得到」，不代表播得動——網址本身
     *  已死、CDN 憑證鏈驗不過都屬於這類，playWithFallback 完全看不到。沒有這組記錄的話，
     *  自動換線會沿著 retryWithNextSource 的環狀順序繞回同一條死線路。換片或換集時清空。 */
    private val failedPlaybackSourceIds = mutableSetOf<Int>()

    init {
        loadPlayer()
    }

    private fun loadPlayer() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, loadingMessage = context.getString(R.string.player_loading_vod)) }
            failedPlaybackSourceIds.clear()
            val id = vodId ?: run {
                _uiState.update { it.copy(isLoading = false, error = context.getString(R.string.common_invalid_vod_id)) }
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
                    val msg = context.getString(
                        if (primary.episodes.isEmpty()) R.string.player_searching_other_sources
                        else R.string.player_resume_searching
                    )
                    _uiState.update { it.copy(loadingMessage = msg) }
                    val enriched = try {
                        vodRepository.getEnrichedVodDetail(sourceType, id, cachedPrimary = primary)
                    } catch (_: Exception) { primary }
                    vodDetail = enriched
                    if (enriched.episodes.isEmpty()) {
                        _uiState.update { it.copy(isLoading = false, error = context.getString(R.string.player_no_sources)) }
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
                        error = context.getString(R.string.player_all_failed, err)
                    ) }
                    return@launch
                }
                enrichWithCrossSource()
            } catch (e: java.io.IOException) {
                _uiState.update { it.copy(isLoading = false, error = context.getString(R.string.common_network_error)) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = context.getString(R.string.player_play_failed, e.message ?: "")) }
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
                lastErr = context.getString(R.string.player_source_no_ep, src.sourceName, episodeNum)
                android.util.Log.w("PlayerFallback",
                    "${src.sourceName}: missing ep$episodeNum (has ${src.episodes.size} eps)")
                continue
            }
            val msg = if (i == 0) context.getString(R.string.player_connecting, src.sourceName)
                else context.getString(R.string.player_fallback, candidates[i - 1].sourceName, src.sourceName)
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
                // 每條線路 8 秒。這個秒數必須傳進去讓 OkHttp 自己計時——外面的 withTimeout
                // 中斷不了阻塞的 execute()，只包一層的話實際等的是網路層 20 秒的絕對上限，
                // 五條線路試下來就是 100 秒的「正在連接…」。
                // withTimeout 仍然留著，它負責擋住 HTTP 以外的部分（解析、DB 查詢）。
                val data = withTimeout(PLAY_BUDGET_MS) {
                    vodRepository.getPlayerData(
                        effectiveSourceType, ep.playUrl, budgetMs = PLAY_BUDGET_MS,
                    )
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
                lastErr = context.getString(R.string.player_timeout_source, src.sourceName, PLAY_BUDGET_MS / 1000)
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
            // 退出播放器那一筆存檔會死在半路：onDispose 發出它之後幾微秒，ViewModel 就被
            // clear、viewModelScope 隨之取消，而 repository 第一步的 Room 查詢是掛起點，
            // 一掛起就再也回不來，後面的 upsert 根本沒機會執行。
            // NonCancellable 讓這段寫入不受 scope 取消影響；viewModelScope 跑在
            // Main.immediate，launch 的 block 會立刻開始，所以一定進得了這個區塊。
            withContext(NonCancellable) {
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
    }

    fun switchEpisode(episodeNum: Int) {
        val detail = vodDetail ?: return
        failedPlaybackSourceIds.clear()
        val ordered = orderedSourcesFrom(detail.episodes, _uiState.value.sourceId)
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, loadingMessage = context.getString(R.string.player_loading_ep, episodeNum)) }
            val err = playWithFallback(ordered, episodeNum, resumeMs = 0L)
            if (err != null) {
                _uiState.update { it.copy(
                    isLoading = false,
                    error = context.getString(R.string.player_all_failed_ep, episodeNum, err)
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
        // 換線不該讓進度歸零——CDN 中途掛掉時使用者可能已經看了半小時，自動換線
        // 卻從第 0 秒重播。resumePositionMs 由自動存檔每 10 秒同步，是最近一次
        // 確實播到的位置。超出新線路片長的情況由播放端在 STATE_READY 時夾住。
        val resumeMs = _uiState.value.resumePositionMs
        viewModelScope.launch {
            _uiState.update { it.copy(
                isLoading = true,
                error = null,
                loadingMessage = context.getString(R.string.player_switching, target.sourceName),
            ) }
            val err = playWithFallback(ordered, episodeNum, resumeMs = resumeMs)
            if (err != null) {
                _uiState.update { it.copy(
                    isLoading = false,
                    error = context.getString(R.string.player_all_failed, err)
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

    /**
     * ExoPlayer 自己重試數次仍播不動時，由 PlayerScreen 呼叫。
     *
     * 補的是取流與播放之間的缺口：[playWithFallback] 只要 getPlayerData 回得了網址就算成功，
     * 之後 ExoPlayer 播不播得起來它一概不知道。實際踩過的案例是影片 CDN 換成 Let's Encrypt
     * 的新根憑證，取流完全正常、ExoPlayer 卻在 TLS 握手就倒，而畫面只是一片全黑，沒有任何
     * 訊息可循。
     *
     * 行為：把目前線路記成「播放失敗」，換到還沒失敗過的下一條；全部都試過就把原因顯示出來。
     */
    fun onPlaybackFailed(reason: String) {
        val detail = vodDetail ?: return
        failedPlaybackSourceIds += _uiState.value.sourceId
        val next = detail.episodes.firstOrNull { it.sourceId !in failedPlaybackSourceIds }
        if (next == null) {
            _uiState.update { it.copy(
                isLoading = false,
                error = context.getString(R.string.player_all_failed_reason, reason),
            ) }
            return
        }
        switchSource(next.sourceId)
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
