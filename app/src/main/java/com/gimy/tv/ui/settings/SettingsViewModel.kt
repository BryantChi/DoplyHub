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
    private val cacheCleaner: com.gimy.tv.data.cache.CacheCleaner,
    endpointResolver: com.gimy.tv.data.endpoint.EndpointResolver,
) : ViewModel() {

    val enabledSources: StateFlow<Set<SourceType>> = sourcePreferencesRepository.enabledSources

    private val _cacheClearing = kotlinx.coroutines.flow.MutableStateFlow(false)
    val cacheClearing: StateFlow<Boolean> = _cacheClearing

    /** 清完後給使用者看的一行結果；再按一次會先清掉。 */
    private val _cacheClearResult = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val cacheClearResult: StateFlow<String?> = _cacheClearResult

    /**
     * 手動清除快取。站方改版或鏡像換網址時，App 這邊可能還抱著舊的回應、舊的封面與
     * 舊的網址，症狀是內容不更新或一直載入失敗；這個按鈕讓使用者不必重裝就能自救。
     */
    fun clearCache() {
        if (_cacheClearing.value) return
        viewModelScope.launch {
            _cacheClearing.value = true
            _cacheClearResult.value = null
            val refreshed = runCatching { cacheCleaner.clearAll() }.getOrDefault(false)
            _cacheClearing.value = false
            _cacheClearResult.value =
                if (refreshed) "已清除快取，站點網址也重新檢查過了。"
                else "已清除快取。站點網址仍在背景檢查，稍後再操作即可生效。"
        }
    }

    /** Last probe result per source, so Settings can surface a silently broken endpoint. */
    val endpointHealth: StateFlow<Map<SourceType, com.gimy.tv.data.endpoint.EndpointHealth>> =
        endpointResolver.health

    fun toggleSource(sourceType: SourceType, enabled: Boolean) {
        viewModelScope.launch {
            sourcePreferencesRepository.setEnabled(sourceType, enabled)
        }
    }
}
