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

data class AdultRowState(
    val items: List<Vod> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class AdultContentScreenViewModel @Inject constructor(
    private val vodRepository: VodRepository,
    sourcePreferencesRepository: SourcePreferencesRepository,
) : ViewModel() {

    val enabledSources: StateFlow<Set<SourceType>> = sourcePreferencesRepository.enabledSources

    /** All adult tabs from currently-enabled sources. Sources can contribute multiple tabs
     *  (gimy.tw has both 39 露骨 and 27 劇情倫理). Order matches enum declaration order. */
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
        fetchInto(tab, flow)
        return flow.asStateFlow()
    }

    fun refreshTab(tab: AdultTab) {
        val flow = rowCache[tab.key] ?: return
        fetchInto(tab, flow)
    }

    private fun fetchInto(tab: AdultTab, flow: MutableStateFlow<AdultRowState>) {
        flow.value = flow.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                withTimeout(10_000) {
                    val items = vodRepository.getVodList(tab.sourceType, tab.typeId, 1).items.take(60)
                    flow.value = AdultRowState(items = items, loading = false, error = null)
                }
            } catch (_: TimeoutCancellationException) {
                flow.value = AdultRowState(items = emptyList(), loading = false, error = "載入超時，請重試")
            } catch (e: Exception) {
                flow.value = AdultRowState(items = emptyList(), loading = false,
                    error = e.message?.takeIf { it.isNotBlank() } ?: "載入失敗")
            }
        }
    }
}
