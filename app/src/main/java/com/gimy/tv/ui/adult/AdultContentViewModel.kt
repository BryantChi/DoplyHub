package com.gimy.tv.ui.adult

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gimy.tv.data.preferences.SourcePreferencesRepository
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.domain.model.categoryMap
import com.gimy.tv.domain.repository.VodRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Lists adult content (typeId = source.categoryMap.adult) per source, lazily.
 * Only sources with adult > 0 AND user-enabled are exposed via [adultSources].
 */
@HiltViewModel
class AdultContentScreenViewModel @Inject constructor(
    private val vodRepository: VodRepository,
    sourcePreferencesRepository: SourcePreferencesRepository,
) : ViewModel() {

    val enabledSources: StateFlow<Set<SourceType>> = sourcePreferencesRepository.enabledSources

    /** Cache of per-source adult listings. First access kicks off fetch. */
    private val rowCache = mutableMapOf<SourceType, MutableStateFlow<List<Vod>>>()

    fun rowFor(sourceType: SourceType): StateFlow<List<Vod>> {
        return rowCache.getOrPut(sourceType) {
            MutableStateFlow<List<Vod>>(emptyList()).also { flow ->
                viewModelScope.launch {
                    val typeId = sourceType.categoryMap.adult
                    if (typeId <= 0) return@launch
                    try {
                        flow.value = vodRepository.getVodList(sourceType, typeId, 1).items.take(60)
                    } catch (_: Exception) {
                        flow.value = emptyList()
                    }
                }
            }
        }
    }

    fun adultSources(enabled: Set<SourceType>): List<SourceType> =
        SourceType.values().filter { it in enabled && it.categoryMap.adult > 0 }
}
