package com.gimy.tv.ui.adultplus

import android.content.Context
import androidx.annotation.StringRes
import com.gimy.tv.R
import dagger.hilt.android.qualifiers.ApplicationContext

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gimy.tv.domain.repository.AdultPlusCatalog
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
    /** 標題放 res id：這份清單是 ViewModel 的 val，標題與 path 要留在同一行才好維護。 */
    @StringRes val titleRes: Int,
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
    private val adultPlusCatalog: AdultPlusCatalog,
    watchHistoryRepository: WatchHistoryRepository,
    favoriteRepository: FavoriteRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** Adult-only watch history surfaced inside AdultPlusScreen. Mapped to Vod
     *  for UI reuse with VodCard. Episode-number suffix doubles as the status badge. */
    val adultHistory: StateFlow<List<Vod>> = watchHistoryRepository.getRecentAdultHistory(20)
        .map { entries ->
            entries.map { e ->
                Vod(
                    id = e.vodId, sourceType = e.sourceType, title = e.title,
                    coverUrl = e.coverUrl, category = "", year = 0,
                    status = context.getString(R.string.common_episode_n, e.episodeNum),
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
        AdultPlusRow(R.string.adultplus_row_jable_hot, SourceType.JABLE_TV, "hot"),
        AdultPlusRow(R.string.adultplus_row_jable_latest, SourceType.JABLE_TV, "latest-updates"),
        AdultPlusRow(R.string.adultplus_row_jable_uncensored, SourceType.JABLE_TV, "categories/uncensored"),
        AdultPlusRow(R.string.adultplus_row_jable_subtitle, SourceType.JABLE_TV, "categories/chinese-subtitle"),
        AdultPlusRow(R.string.adultplus_row_jable_roleplay, SourceType.JABLE_TV, "categories/roleplay"),
        AdultPlusRow(R.string.adultplus_row_jable_uniform, SourceType.JABLE_TV, "categories/uniform"),
        AdultPlusRow(R.string.adultplus_row_jable_private_cam, SourceType.JABLE_TV, "categories/private-cam"),
        // XNXX — /best/{period}, /tags/{slug}
        AdultPlusRow(R.string.adultplus_row_xnxx_week, SourceType.XNXX, "best/this_week"),
        AdultPlusRow(R.string.adultplus_row_xnxx_month, SourceType.XNXX, "best/this_month"),
        AdultPlusRow(R.string.adultplus_row_xnxx_today, SourceType.XNXX, "best/today"),
        AdultPlusRow(R.string.adultplus_row_xnxx_asian, SourceType.XNXX, "tags/asian"),
        AdultPlusRow(R.string.adultplus_row_xnxx_japanese, SourceType.XNXX, "tags/japanese"),
        AdultPlusRow(R.string.adultplus_row_xnxx_chinese, SourceType.XNXX, "tags/chinese"),
        // 5278 — Discuz forums
        AdultPlusRow(R.string.adultplus_row_5278_adult, SourceType.FORUM5278, "forum:23"),
        AdultPlusRow(R.string.adultplus_row_5278_sexy, SourceType.FORUM5278, "forum:42"),
    )

    /** Pull-to-refresh visual state. Without this, RefreshableContainer always sees
     *  isRefreshing=false and the spinner never appears, making refresh look broken. */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val rowCache = mutableMapOf<String, MutableStateFlow<AdultPlusRowState>>()

    /** 純讀取，不發請求。抓取由畫面的 LaunchedEffect 觸發（見 [ensureRow]）。 */
    fun rowState(row: AdultPlusRow): StateFlow<AdultPlusRowState> =
        rowCache.getOrPut(cacheKey(row)) {
            MutableStateFlow(AdultPlusRowState(loading = true))
        }.asStateFlow()

    /** 這一列第一次進入畫面時抓資料。理由同 [AdultContentViewModel.ensureRow]。 */
    fun ensureRow(row: AdultPlusRow) {
        val key = cacheKey(row)
        val flow = rowCache[key] ?: MutableStateFlow(AdultPlusRowState(loading = true))
            .also { rowCache[key] = it }
        if (flow.value.items.isNotEmpty() || flow.value.error != null) return
        fetch(row, flow)
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
                    val result = adultPlusCatalog.fetchByPath(row.sourceType, row.key, page)
                    val limited = result.items.take(24)
                    val combined = if (append) flow.value.items + limited else limited
                    val unique = combined.distinctBy { "${it.sourceType}_${it.id}" }
                    val gotNew = !append || unique.size > flow.value.items.size

                    if (unique.isEmpty() && row.sourceType == SourceType.FORUM5278) {
                        flow.value = AdultPlusRowState(items = emptyList(), loading = false,
                            loadingMore = false, error = context.getString(R.string.common_site_maintenance),
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
                    error = if (append) null else context.getString(R.string.adultplus_load_timeout),
                    hasMore = if (append) false else flow.value.hasMore)
            } catch (e: Exception) {
                val msg = if (row.sourceType == SourceType.FORUM5278) context.getString(R.string.common_site_maintenance)
                else e.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.common_load_failed)
                flow.value = flow.value.copy(loading = false, loadingMore = false,
                    error = if (append) null else msg,
                    hasMore = if (append) false else flow.value.hasMore)
            }
        }
    }
}
