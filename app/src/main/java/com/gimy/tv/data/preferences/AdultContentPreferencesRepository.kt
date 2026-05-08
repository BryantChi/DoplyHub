package com.gimy.tv.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private val Context.adultContentDataStore: DataStore<Preferences> by preferencesDataStore(name = "adult_content")

/**
 * Adult-content gate state. Keeps disk-backed flags (enabled / PIN-required / PIN-hash /
 * fail-count / locked-until) and one in-memory flag (`unlocked`) that resets per cold start —
 * even if the user passed PIN earlier in this session, restarting the app re-locks the zone.
 *
 * Failure backoff: 3 wrong PIN entries trigger a 5-minute lockout. The lock is enforced
 * against `System.currentTimeMillis()` so killing the app doesn't bypass it.
 */
@Singleton
class AdultContentPreferencesRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    companion object {
        const val MAX_FAIL_BEFORE_LOCK = 3
        const val LOCK_DURATION_MS = 5L * 60L * 1000L
        private val KEY_ENABLED = booleanPreferencesKey("adult_enabled")
        private val KEY_PIN_REQUIRED = booleanPreferencesKey("adult_pin_required")
        private val KEY_PIN_HASH = stringPreferencesKey("adult_pin_hash")
        private val KEY_FAIL_COUNT = intPreferencesKey("adult_fail_count")
        private val KEY_LOCKED_UNTIL = longPreferencesKey("adult_locked_until_ms")
        // Phase 6 — Advanced adult sources (jable / xnxx / 5278). Off by default.
        private val KEY_ADULT_PLUS_ENABLED = booleanPreferencesKey("adult_plus_enabled")
    }

    private val dataStore = context.adultContentDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val enabled: StateFlow<Boolean> = dataStore.data
        .map { it[KEY_ENABLED] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val pinRequired: StateFlow<Boolean> = dataStore.data
        .map { it[KEY_PIN_REQUIRED] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val pinHash: StateFlow<String?> = dataStore.data
        .map { it[KEY_PIN_HASH] }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val failCount: StateFlow<Int> = dataStore.data
        .map { it[KEY_FAIL_COUNT] ?: 0 }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    val lockedUntilMs: StateFlow<Long> = dataStore.data
        .map { it[KEY_LOCKED_UNTIL] ?: 0L }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    /** Phase 6 toggle for advanced adult sources (jable.tv / xnxx.com / 5278.cc).
     *  Off by default; user must explicitly opt in via Settings. */
    val adultPlusEnabled: StateFlow<Boolean> = dataStore.data
        .map { it[KEY_ADULT_PLUS_ENABLED] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** In-memory only — resets to false on cold start. Set when user passes PIN this session. */
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    fun isLocked(nowMs: Long = System.currentTimeMillis()): Boolean =
        lockedUntilMs.value > nowMs

    fun remainingLockSeconds(nowMs: Long = System.currentTimeMillis()): Long =
        ((lockedUntilMs.value - nowMs) / 1000).coerceAtLeast(0)

    suspend fun setEnabled(value: Boolean) {
        dataStore.edit { it[KEY_ENABLED] = value }
        if (!value) {
            _unlocked.value = false  // disabling resets session unlock
            // Disabling the master switch implicitly disables advanced sources too
            dataStore.edit { it[KEY_ADULT_PLUS_ENABLED] = false }
        }
    }

    suspend fun setAdultPlusEnabled(value: Boolean) {
        dataStore.edit { it[KEY_ADULT_PLUS_ENABLED] = value }
    }

    suspend fun setPinRequired(value: Boolean) {
        dataStore.edit { it[KEY_PIN_REQUIRED] = value }
    }

    /** Stores SHA-256 hash; never persists the plaintext. */
    suspend fun setPin(pin: String) {
        require(pin.length == 4 && pin.all { it.isDigit() }) { "PIN must be 4 digits" }
        dataStore.edit {
            it[KEY_PIN_HASH] = sha256(pin)
            it[KEY_FAIL_COUNT] = 0
            it[KEY_LOCKED_UNTIL] = 0L
        }
    }

    suspend fun clearPin() {
        dataStore.edit {
            it.remove(KEY_PIN_HASH)
            it[KEY_PIN_REQUIRED] = false
            it[KEY_FAIL_COUNT] = 0
            it[KEY_LOCKED_UNTIL] = 0L
        }
    }

    /** Returns true if input matches stored PIN. Updates fail count / lock timer accordingly. */
    suspend fun verifyPin(input: String): Boolean {
        val storedHash = dataStore.data.first()[KEY_PIN_HASH]
        if (storedHash == null) return true  // no PIN set means no gate
        if (sha256(input) == storedHash) {
            dataStore.edit {
                it[KEY_FAIL_COUNT] = 0
                it[KEY_LOCKED_UNTIL] = 0L
            }
            _unlocked.value = true
            return true
        }
        // Wrong PIN — bump fail count, lock if threshold exceeded
        dataStore.edit { prefs ->
            val nextFails = (prefs[KEY_FAIL_COUNT] ?: 0) + 1
            if (nextFails >= MAX_FAIL_BEFORE_LOCK) {
                prefs[KEY_LOCKED_UNTIL] = System.currentTimeMillis() + LOCK_DURATION_MS
                prefs[KEY_FAIL_COUNT] = 0  // reset count once lock starts
            } else {
                prefs[KEY_FAIL_COUNT] = nextFails
            }
        }
        return false
    }

    fun markUnlocked() { _unlocked.value = true }
    fun clearSessionUnlock() { _unlocked.value = false }

    /** Wipes everything: opt-out of adult zone entirely. */
    suspend fun resetAll() {
        dataStore.edit { it.clear() }
        _unlocked.value = false
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
