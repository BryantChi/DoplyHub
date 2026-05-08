package com.gimy.tv.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gimy.tv.data.preferences.AdultContentPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AdultContentViewModel @Inject constructor(
    private val repo: AdultContentPreferencesRepository,
) : ViewModel() {
    val enabled: StateFlow<Boolean> = repo.enabled
    val pinRequired: StateFlow<Boolean> = repo.pinRequired
    val pinHash: StateFlow<String?> = repo.pinHash
    val unlocked: StateFlow<Boolean> = repo.unlocked
    val lockedUntilMs: StateFlow<Long> = repo.lockedUntilMs
    val failCount: StateFlow<Int> = repo.failCount

    fun setEnabled(value: Boolean) = viewModelScope.launch { repo.setEnabled(value) }
    fun setPinRequired(value: Boolean) = viewModelScope.launch { repo.setPinRequired(value) }
    fun savePin(pin: String) = viewModelScope.launch { repo.setPin(pin) }
    fun clearPin() = viewModelScope.launch { repo.clearPin() }
    fun resetAll() = viewModelScope.launch { repo.resetAll() }

    suspend fun verifyPin(input: String): Boolean = repo.verifyPin(input)
    fun isLocked(): Boolean = repo.isLocked()
    fun remainingLockSeconds(): Long = repo.remainingLockSeconds()
    fun markUnlocked() = repo.markUnlocked()
}
