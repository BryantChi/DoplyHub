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

/** Per-source row state: tracks loading + result + error so the UI can distinguish
 *  "still loading" from "loaded but empty" from "failed". Without this users couldn't
 *  tell whether to wait, retry, or give up — they reported "卡很久都沒反應". */
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

    private val rowCache = mutableMapOf<SourceType, MutableStateFlow<AdultRowState>>()

    fun rowFor(sourceType: SourceType): StateFlow<AdultRowState> {
        val existing = rowCache[sourceType]
        if (existing != null) return existing.asStateFlow()
        val flow = MutableStateFlow(AdultRowState(loading = true))
        rowCache[sourceType] = flow
        fetchInto(sourceType, flow)
        return flow.asStateFlow()
    }

    /** Force re-fetch for one source (used by retry button). */
    fun refreshSource(sourceType: SourceType) {
        val flow = rowCache[sourceType] ?: return
        fetchInto(sourceType, flow)
    }

    /** Force re-fetch all currently-cached sources (used by pull-to-refresh). */
    fun refreshAll() {
        rowCache.forEach { (src, flow) -> fetchInto(src, flow) }
    }

    private fun fetchInto(sourceType: SourceType, flow: MutableStateFlow<AdultRowState>) {
        val typeId = sourceType.categoryMap.adult
        if (typeId <= 0) {
            flow.value = AdultRowState(loading = false, error = "此來源無 18+ 分區")
            return
        }
        flow.value = flow.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                withTimeout(10_000) {
                    val items = vodRepository.getVodList(sourceType, typeId, 1).items.take(60)
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

    fun adultSources(enabled: Set<SourceType>): List<SourceType> =
        SourceType.values().filter { it in enabled && it.categoryMap.adult > 0 }
}
