package com.mmcdole.tether.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.mmcdole.tether.data.settings.AppSettings.Companion.MAX_TEXT_SIZE
import com.mmcdole.tether.data.settings.AppSettings.Companion.MIN_TEXT_SIZE
import com.mmcdole.tether.data.settings.AppSettings.Companion.SCROLLBACK_CHOICES
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** App settings in DataStore Preferences. */
@Singleton
class SettingsRepository @Inject constructor(private val store: DataStore<Preferences>) {
    val settings: Flow<AppSettings> = store.data.map { it.toSettings() }.distinctUntilChanged()

    /** Whether the first-run prompts for notifications and battery optimization have been shown. */
    val askedNotifications: Flow<Boolean> = store.data.map { it[ASKED_NOTIFICATIONS] == true }
    val askedBattery: Flow<Boolean> = store.data.map { it[ASKED_BATTERY] == true }

    suspend fun setAskedNotifications() = store.edit { it[ASKED_NOTIFICATIONS] = true }

    suspend fun setAskedBattery() = store.edit { it[ASKED_BATTERY] = true }

    suspend fun setTextSize(sp: Int) = store.edit { it[TEXT_SIZE] = sp.coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE) }

    suspend fun setHideStatusBar(on: Boolean) = store.edit { it[HIDE_STATUS_BAR] = on }

    suspend fun setAutocorrect(on: Boolean) = store.edit { it[AUTOCORRECT] = on }

    suspend fun setKeepKeyboardOpen(on: Boolean) = store.edit { it[KEEP_KEYBOARD_OPEN] = on }

    suspend fun setAutoReconnect(on: Boolean) = store.edit { it[AUTO_RECONNECT] = on }

    suspend fun setWifiLock(on: Boolean) = store.edit { it[WIFI_LOCK] = on }

    suspend fun setCpuWakeLock(on: Boolean) = store.edit { it[CPU_WAKE_LOCK] = on }

    suspend fun setScrollbackLines(lines: Int) {
        require(lines in SCROLLBACK_CHOICES)
        store.edit { it[SCROLLBACK_LINES] = lines }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            textSize = this[TEXT_SIZE]?.coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE) ?: defaults.textSize,
            hideStatusBar = this[HIDE_STATUS_BAR] ?: defaults.hideStatusBar,
            autocorrect = this[AUTOCORRECT] ?: defaults.autocorrect,
            keepKeyboardOpen = this[KEEP_KEYBOARD_OPEN] ?: defaults.keepKeyboardOpen,
            autoReconnect = this[AUTO_RECONNECT] ?: defaults.autoReconnect,
            wifiLock = this[WIFI_LOCK] ?: defaults.wifiLock,
            cpuWakeLock = this[CPU_WAKE_LOCK] ?: defaults.cpuWakeLock,
            scrollbackLines = this[SCROLLBACK_LINES]?.takeIf { it in SCROLLBACK_CHOICES } ?: defaults.scrollbackLines,
        )
    }

    private companion object {
        val ASKED_NOTIFICATIONS = booleanPreferencesKey("asked_notifications")
        val ASKED_BATTERY = booleanPreferencesKey("asked_battery")
        val TEXT_SIZE = intPreferencesKey("text_size")
        val HIDE_STATUS_BAR = booleanPreferencesKey("hide_status_bar")
        val AUTOCORRECT = booleanPreferencesKey("autocorrect")
        val KEEP_KEYBOARD_OPEN = booleanPreferencesKey("keep_keyboard_open")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val WIFI_LOCK = booleanPreferencesKey("wifi_lock")
        val CPU_WAKE_LOCK = booleanPreferencesKey("cpu_wake_lock")
        val SCROLLBACK_LINES = intPreferencesKey("scrollback_lines")
    }
}
