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
    /** Currently-loaded page (1-based). Each loadMore() bumps this. */
    val currentPage: Int = 1,
    /** Whether the source reports more pages available. Drives "→ 更多" button. */
    val hasMore: Boolean = false,
    /** True while a loadMore() is in flight; UI shows spinner instead of "更多" button. */
    val loadingMore: Boolean = false,
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
        // Jable — paths verified against jable.tv/categories/ index
        AdultPlusRow("🔥 Jable 熱門", SourceType.JABLE_TV, "hot"),
        AdultPlusRow("🆕 Jable 最新", SourceType.JABLE_TV, "latest-updates"),
        AdultPlusRow("🈲 Jable 無碼解放", SourceType.JABLE_TV, "categories/uncensored"),
        AdultPlusRow("🈳 Jable 中文字幕", SourceType.JABLE_TV, "categories/chinese-subtitle"),
        AdultPlusRow("🎭 Jable 角色劇情", SourceType.JABLE_TV, "categories/roleplay"),
        AdultPlusRow("🎓 Jable 制服誘惑", SourceType.JABLE_TV, "categories/uniform"),
        AdultPlusRow("👀 Jable 盜攝偷拍", SourceType.JABLE_TV, "categories/private-cam"),
        // XNXX — /best/{period}, /tags/{slug}
        AdultPlusRow("📈 XNXX 本週最佳", SourceType.XNXX, "best/this_week"),
        AdultPlusRow("🌏 XNXX 本月最佳", SourceType.XNXX, "best/this_month"),
        AdultPlusRow("📅 XNXX 今日最佳", SourceType.XNXX, "best/today"),
        AdultPlusRow("🌸 XNXX 亞洲", SourceType.XNXX, "tags/asian"),
        AdultPlusRow("🎌 XNXX 日本", SourceType.XNXX, "tags/japanese"),
        AdultPlusRow("👩 XNXX 中文", SourceType.XNXX, "tags/chinese"),
        // 5278 — Discuz forums
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

    /** Fetch a row's first page (initial load OR pull-to-refresh). */
    private fun fetch(row: AdultPlusRow, flow: MutableStateFlow<AdultPlusRowState>) {
        fetchPage(row, flow, page = 1, append = false)
    }

    /** Append the next page to an existing row. Driven by the "→ 更多" button. */
    fun loadMore(row: AdultPlusRow) {
        val flow = rowCache[cacheKey(row)] ?: return
        val state = flow.value
        if (state.loading || state.loadingMore || !state.hasMore) return
        fetchPage(row, flow, page = state.currentPage + 1, append = true)
    }

    private fun fetchPage(
        row: AdultPlusRow,
        flow: MutableStateFlow<AdultPlusRowState>,
        page: Int,
        append: Boolean,
    ) {
        flow.value = if (append) flow.value.copy(loadingMore = true, error = null)
        else flow.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                withTimeout(12_000) {
                    val result = when (row.sourceType) {
                        SourceType.JABLE_TV -> jableSource.fetchVodListByPath(row.key, page)
                        SourceType.XNXX -> xnxxSource.fetchVodListByPath(row.key, page)
                        SourceType.FORUM5278 -> {
                            val forumId = row.key.removePrefix("forum:").toIntOrNull() ?: 23
                            forum5278Source.fetchVodList(forumId, page)
                        }
                        else -> com.gimy.tv.domain.model.PaginatedResult(emptyList<Vod>(), page, 0, false)
                    }
                    val limited = result.items.take(24)
                    val combined = if (append) flow.value.items + limited else limited
                    val unique = combined.distinctBy { "${it.sourceType}_${it.id}" }
                    val gotNew = !append || unique.size > flow.value.items.size

                    if (unique.isEmpty() && row.sourceType == SourceType.FORUM5278) {
                        flow.value = AdultPlusRowState(items = emptyList(), loading = false,
                            loadingMore = false, error = "站方維護中，稍後再試",
                            currentPage = 1, hasMore = false)
                    } else {
                        flow.value = AdultPlusRowState(
                            items = unique, loading = false, loadingMore = false, error = null,
                            currentPage = page,
                            hasMore = result.hasMore && gotNew,
                        )
                    }
                }
            } catch (_: TimeoutCancellationException) {
                flow.value = flow.value.copy(loading = false, loadingMore = false,
                    error = if (append) null else "載入超時",
                    hasMore = if (append) false else flow.value.hasMore)
            } catch (e: Exception) {
                val msg = if (row.sourceType == SourceType.FORUM5278) "站方維護中，稍後再試"
                else e.message?.takeIf { it.isNotBlank() } ?: "載入失敗"
                flow.value = flow.value.copy(loading = false, loadingMore = false,
                    error = if (append) null else msg,
                    hasMore = if (append) false else flow.value.hasMore)
            }
        }
    }
}
