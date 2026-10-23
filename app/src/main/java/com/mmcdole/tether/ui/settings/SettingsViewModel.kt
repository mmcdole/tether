package com.mmcdole.tether.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mmcdole.tether.data.settings.AppSettings
import com.mmcdole.tether.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(private val settings: SettingsRepository) : ViewModel() {
    /** Null until loaded. */
    val state: StateFlow<AppSettings?> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setTextSize(sp: Int) = save { settings.setTextSize(sp) }

    fun setHideStatusBar(on: Boolean) = save { settings.setHideStatusBar(on) }

    fun setAutocorrect(on: Boolean) = save { settings.setAutocorrect(on) }

    fun setKeepKeyboardOpen(on: Boolean) = save { settings.setKeepKeyboardOpen(on) }

    fun setAutoReconnect(on: Boolean) = save { settings.setAutoReconnect(on) }

    fun setWifiLock(on: Boolean) = save { settings.setWifiLock(on) }

    fun setCpuWakeLock(on: Boolean) = save { settings.setCpuWakeLock(on) }

    fun setScrollbackLines(lines: Int) = save { settings.setScrollbackLines(lines) }

    private fun save(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
