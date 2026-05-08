package com.gimy.tv.ui.adultplus

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gimy.tv.data.scraper.Forum5278Source
import com.gimy.tv.data.scraper.JableTvSource
import com.gimy.tv.data.scraper.XnxxSource
import com.gimy.tv.domain.model.PaginatedResult
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.net.URLDecoder
import javax.inject.Inject

data class AdultPlusBrowseState(
    val sourceType: SourceType? = null,
    val pathKey: String = "",
    val title: String = "",
    val items: List<Vod> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val currentPage: Int = 1,
    val hasMore: Boolean = false,
    val isRefreshing: Boolean = false,
)

/**
 * Per-row "查看更多" page. Mirrors HomeScreen → BrowseScreen pattern but uses
 * path-based fetching (jable / xnxx use opaque path keys, 5278 uses "forum:N").
 *
 * Navigation passes (sourceType, pathKey, title) via SavedStateHandle args.
 */
@HiltViewModel
class AdultPlusBrowseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val jableSource: JableTvSource,
    private val xnxxSource: XnxxSource,
    private val forum5278Source: Forum5278Source,
) : ViewModel() {

    private val _state = MutableStateFlow(AdultPlusBrowseState())
    val state: StateFlow<AdultPlusBrowseState> = _state.asStateFlow()

    init {
        val sourceTypeName: String = savedStateHandle["sourceType"] ?: ""
        val rawPath: String = savedStateHandle["path"] ?: ""
        val titleArg: String = savedStateHandle["title"] ?: ""
        val sourceType = runCatching { SourceType.valueOf(sourceTypeName) }.getOrNull()
        val path = runCatching { URLDecoder.decode(rawPath, "UTF-8") }.getOrDefault(rawPath)
        val title = runCatching { URLDecoder.decode(titleArg, "UTF-8") }.getOrDefault(titleArg)

        _state.value = _state.value.copy(sourceType = sourceType, pathKey = path, title = title)
        if (sourceType != null && path.isNotBlank()) fetchPage(1, append = false)
    }

    fun refresh() {
        if (_state.value.isRefreshing) return
        _state.value = _state.value.copy(isRefreshing = true)
        fetchPage(1, append = false)
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.loadingMore || !s.hasMore) return
        fetchPage(s.currentPage + 1, append = true)
    }

    private fun fetchPage(page: Int, append: Boolean) {
        val s = _state.value
        val src = s.sourceType ?: return
        val path = s.pathKey
        _state.value = if (append) s.copy(loadingMore = true, error = null)
        else s.copy(loading = !append && s.items.isEmpty(), error = null)

        viewModelScope.launch {
            try {
                withTimeout(15_000) {
                    val result: PaginatedResult<Vod> = when (src) {
                        SourceType.JABLE_TV -> jableSource.fetchVodListByPath(path, page)
                        SourceType.XNXX -> xnxxSource.fetchVodListByPath(path, page)
                        SourceType.FORUM5278 -> {
                            val forumId = path.removePrefix("forum:").toIntOrNull() ?: 23
                            forum5278Source.fetchVodList(forumId, page)
                        }
                        else -> PaginatedResult(emptyList(), page, 0, false)
                    }
                    val combined = if (append) _state.value.items + result.items else result.items
                    val unique = combined.distinctBy { "${it.sourceType}_${it.id}" }
                    val gotNew = !append || unique.size > _state.value.items.size
                    _state.value = _state.value.copy(
                        items = unique,
                        loading = false,
                        loadingMore = false,
                        isRefreshing = false,
                        error = null,
                        currentPage = page,
                        hasMore = result.hasMore && gotNew,
                    )
                }
            } catch (_: TimeoutCancellationException) {
                _state.value = _state.value.copy(loading = false, loadingMore = false,
                    isRefreshing = false,
                    error = if (append) null else "載入超時，請重試",
                    hasMore = if (append) false else _state.value.hasMore)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, loadingMore = false,
                    isRefreshing = false,
                    error = if (append) null else (e.message ?: "載入失敗"),
                    hasMore = if (append) false else _state.value.hasMore)
            }
        }
    }
}
