package com.gimy.tv.ui.adult

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gimy.tv.data.preferences.SourcePreferencesRepository
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.domain.model.categoryMap
import com.gimy.tv.domain.repository.VodRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/** A single tab in the 18+ zone — uniquely identified by (source, typeId).
 *  gimy.tw contributes two tabs (露骨 39 / 劇情倫理 27); other sites one each. */
data class AdultTab(
    val sourceType: SourceType,
    val typeId: Int,
    val label: String,
) {
    val key: String get() = "${sourceType.name}_$typeId"
}

/** Per-tab paginated state. `hasMore` drives infinite scroll; `loadingMore` shows the
 *  bottom spinner without flipping the main `loading` (which would blank the grid). */
data class AdultRowState(
    val items: List<Vod> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val currentPage: Int = 1,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
)

@HiltViewModel
class AdultContentScreenViewModel @Inject constructor(
    private val vodRepository: VodRepository,
    sourcePreferencesRepository: SourcePreferencesRepository,
) : ViewModel() {

    val enabledSources: StateFlow<Set<SourceType>> = sourcePreferencesRepository.enabledSources

    fun adultTabs(enabled: Set<SourceType>): List<AdultTab> =
        SourceType.values()
            .filter { it in enabled }
            .flatMap { src ->
                src.categoryMap.adultCategories.map { entry ->
                    AdultTab(src, entry.typeId, entry.label)
                }
            }

    private val rowCache = mutableMapOf<String, MutableStateFlow<AdultRowState>>()

    fun rowFor(tab: AdultTab): StateFlow<AdultRowState> {
        val existing = rowCache[tab.key]
        if (existing != null) return existing.asStateFlow()
        val flow = MutableStateFlow(AdultRowState(loading = true))
        rowCache[tab.key] = flow
        fetchPage(tab, flow, page = 1, append = false)
        return flow.asStateFlow()
    }

    /** Pull-to-refresh: reset to page 1, replace items. */
    fun refreshTab(tab: AdultTab) {
        val flow = rowCache[tab.key] ?: return
        fetchPage(tab, flow, page = 1, append = false)
    }

    /** Infinite scroll: append next page if not already loading and there's more. */
    fun loadMore(tab: AdultTab) {
        val flow = rowCache[tab.key] ?: return
        val state = flow.value
        if (state.loading || state.loadingMore || !state.hasMore) return
        fetchPage(tab, flow, page = state.currentPage + 1, append = true)
    }

    private fun fetchPage(
        tab: AdultTab,
        flow: MutableStateFlow<AdultRowState>,
        page: Int,
        append: Boolean,
    ) {
        // Update loading state without dropping items on append (the grid stays put)
        flow.value = if (append) {
            flow.value.copy(loadingMore = true, error = null)
        } else {
            flow.value.copy(loading = true, error = null)
        }
        viewModelScope.launch {
            try {
                withTimeout(10_000) {
                    val result = vodRepository.getVodList(tab.sourceType, tab.typeId, page)
                    val combined = if (append) flow.value.items + result.items else result.items
                    val unique = combined.distinctBy { "${it.sourceType}_${it.id}" }
                    val gotNew = !append || unique.size > flow.value.items.size
                    flow.value = AdultRowState(
                        items = unique,
                        loading = false,
                        loadingMore = false,
                        error = null,
                        currentPage = page,
                        hasMore = result.hasMore && gotNew,
                    )
                }
            } catch (_: TimeoutCancellationException) {
                flow.value = flow.value.copy(
                    loading = false, loadingMore = false,
                    // On append-timeout keep existing items; for first-page failure surface error
                    error = if (append) null else "載入超時，請重試",
                    hasMore = if (append) false else flow.value.hasMore,
                )
            } catch (e: Exception) {
                flow.value = flow.value.copy(
                    loading = false, loadingMore = false,
                    error = if (append) null else (e.message?.takeIf { it.isNotBlank() } ?: "載入失敗"),
                    hasMore = if (append) false else flow.value.hasMore,
                )
            }
        }
    }
}
