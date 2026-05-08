package com.gimy.tv.ui.adultplus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gimy.tv.data.scraper.Forum5278Source
import com.gimy.tv.data.scraper.JableTvSource
import com.gimy.tv.data.scraper.XnxxSource
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.domain.repository.FavoriteRepository
import com.gimy.tv.domain.repository.WatchHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/** One row in the AdultPlusScreen — combines a source with a path/typeId selector. */
data class AdultPlusRow(
    val title: String,
    val sourceType: SourceType,
    /** "forum:N" for 5278, otherwise an opaque path key passed to fetchVodListByPath */
    val key: String,
)

data class AdultPlusRowState(
    val items: List<Vod> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class AdultPlusViewModel @Inject constructor(
    private val jableSource: JableTvSource,
    private val xnxxSource: XnxxSource,
    private val forum5278Source: Forum5278Source,
    watchHistoryRepository: WatchHistoryRepository,
    favoriteRepository: FavoriteRepository,
) : ViewModel() {

    /** Adult-only watch history surfaced inside AdultPlusScreen. Mapped to Vod
     *  for UI reuse with VodCard. Episode-number suffix doubles as the status badge. */
    val adultHistory: StateFlow<List<Vod>> = watchHistoryRepository.getRecentAdultHistory(20)
        .map { entries ->
            entries.map { e ->
                Vod(
                    id = e.vodId, sourceType = e.sourceType, title = e.title,
                    coverUrl = e.coverUrl, category = "", year = 0,
                    status = "第${e.episodeNum}集",
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val adultFavorites: StateFlow<List<Vod>> = favoriteRepository.getAdultFavorites()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Curated row list. Each row fans out to one source. The order intentionally
     *  alternates jable / xnxx so the user sees variety scrolling vertically. */
    /** Curated row list. 5278 forums sometimes return Discuz「提示信息」(maintenance /
     *  temporary block) — we still surface them so the row reappears once the site recovers,
     *  but fetch() catches the empty result and reports "站方維護中，稍後重試". */
    val rows: List<AdultPlusRow> = listOf(
        AdultPlusRow("🔥 Jable 熱門", SourceType.JABLE_TV, "hot"),
        AdultPlusRow("📈 XNXX 本週最佳", SourceType.XNXX, "best/this_week"),
        AdultPlusRow("🆕 Jable 最新", SourceType.JABLE_TV, "latest-updates"),
        AdultPlusRow("🌏 XNXX 本月最佳", SourceType.XNXX, "best/this_month"),
        AdultPlusRow("💬 5278 成人線上", SourceType.FORUM5278, "forum:23"),
        AdultPlusRow("💬 5278 線上性感影片", SourceType.FORUM5278, "forum:42"),
    )

    /** Pull-to-refresh visual state. Without this, RefreshableContainer always sees
     *  isRefreshing=false and the spinner never appears, making refresh look broken. */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val rowCache = mutableMapOf<String, MutableStateFlow<AdultPlusRowState>>()

    fun rowState(row: AdultPlusRow): StateFlow<AdultPlusRowState> {
        val key = cacheKey(row)
        val existing = rowCache[key]
        if (existing != null) return existing.asStateFlow()
        val flow = MutableStateFlow(AdultPlusRowState(loading = true))
        rowCache[key] = flow
        fetch(row, flow)
        return flow.asStateFlow()
    }

    fun refreshRow(row: AdultPlusRow) {
        val flow = rowCache[cacheKey(row)] ?: return
        fetch(row, flow)
    }

    fun refreshAll() {
        _isRefreshing.value = true
        viewModelScope.launch {
            // Re-fetch each row that's been touched. fetch() launches its own coroutine
            // so this loop returns immediately; we use a separate coroutine to flip
            // isRefreshing back off after a short delay so the spinner is visible.
            rows.forEach { row ->
                rowCache[cacheKey(row)]?.let { fetch(row, it) }
            }
            kotlinx.coroutines.delay(800)
            _isRefreshing.value = false
        }
    }

    private fun cacheKey(row: AdultPlusRow): String =
        "${row.sourceType.name}|${row.key}"

    private fun fetch(row: AdultPlusRow, flow: MutableStateFlow<AdultPlusRowState>) {
        flow.value = flow.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                withTimeout(12_000) {
                    val items = when (row.sourceType) {
                        SourceType.JABLE_TV ->
                            jableSource.fetchVodListByPath(row.key, 1).items.take(24)
                        SourceType.XNXX ->
                            xnxxSource.fetchVodListByPath(row.key, 1).items.take(24)
                        SourceType.FORUM5278 -> {
                            val forumId = row.key.removePrefix("forum:").toIntOrNull() ?: 23
                            forum5278Source.fetchVodList(forumId, 1).items.take(24)
                        }
                        else -> emptyList()
                    }
                    // 5278 occasionally returns 0 items because the whole forum is in
                    // maintenance mode (Discuz "提示信息" wall). Surface a friendlier
                    // message so users know to retry later instead of thinking the App is broken.
                    if (items.isEmpty() && row.sourceType == SourceType.FORUM5278) {
                        flow.value = AdultPlusRowState(items = emptyList(), loading = false,
                            error = "站方維護中，稍後再試")
                    } else {
                        flow.value = AdultPlusRowState(items = items, loading = false, error = null)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                flow.value = AdultPlusRowState(items = emptyList(), loading = false, error = "載入超時")
            } catch (e: Exception) {
                val msg = if (row.sourceType == SourceType.FORUM5278) "站方維護中，稍後再試"
                else e.message?.takeIf { it.isNotBlank() } ?: "載入失敗"
                flow.value = AdultPlusRowState(items = emptyList(), loading = false, error = msg)
            }
        }
    }
}
