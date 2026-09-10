package com.gimy.tv.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gimy.tv.data.preferences.SourcePreferencesRepository
import com.gimy.tv.domain.model.SourceType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val sourcePreferencesRepository: SourcePreferencesRepository,
    endpointResolver: com.gimy.tv.data.endpoint.EndpointResolver,
) : ViewModel() {

    val enabledSources: StateFlow<Set<SourceType>> = sourcePreferencesRepository.enabledSources

    /** Last probe result per source, so Settings can surface a silently broken endpoint. */
    val endpointHealth: StateFlow<Map<SourceType, com.gimy.tv.data.endpoint.EndpointHealth>> =
        endpointResolver.health

    fun toggleSource(sourceType: SourceType, enabled: Boolean) {
        viewModelScope.launch {
            sourcePreferencesRepository.setEnabled(sourceType, enabled)
        }
    }
}
