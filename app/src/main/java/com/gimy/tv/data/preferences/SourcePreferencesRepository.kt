package com.gimy.tv.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.gimy.tv.domain.model.SourceType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sourcePreferencesDataStore: DataStore<Preferences> by preferencesDataStore(name = "source_preferences")

/**
 * Persists per-SourceType enable/disable flags. Default = enabled (so a fresh install
 * sees all 8 sources). Stored values only written when the user explicitly toggles a
 * source off — that way new SourceTypes added in future releases are auto-enabled.
 */
@Singleton
class SourcePreferencesRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val dataStore = context.sourcePreferencesDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Set of currently-enabled sources. Eagerly hot — VodRepositoryImpl reads `.value` synchronously. */
    val enabledSources: StateFlow<Set<SourceType>> = dataStore.data
        .map { prefs ->
            SourceType.values().filter { type ->
                prefs[keyFor(type)] ?: true
            }.toSet()
        }
        .stateIn(scope, SharingStarted.Eagerly, SourceType.values().toSet())

    suspend fun setEnabled(sourceType: SourceType, enabled: Boolean) {
        dataStore.edit { it[keyFor(sourceType)] = enabled }
    }

    /**
     * Cold-start-safe snapshot of currently-enabled sources.
     *
     * `enabledSources.value` returns the seed (all sources enabled) until the underlying
     * DataStore finishes its first read — that race lets cold-start code paths
     * (search, enrichment) run against a wider source set than the user actually wants,
     * polluting the 60s cache for that long. Reading `dataStore.data.first()` directly
     * suspends until the real value is available, regardless of stateIn timing.
     */
    suspend fun snapshot(): Set<SourceType> {
        val prefs = dataStore.data.first()
        return SourceType.values().filter { prefs[keyFor(it)] ?: true }.toSet()
    }

    private fun keyFor(type: SourceType) = booleanPreferencesKey("source_${type.name}")
}
