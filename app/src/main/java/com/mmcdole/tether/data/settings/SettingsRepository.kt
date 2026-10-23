package com.mmcdole.tether.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** App settings in DataStore Preferences. */
@Singleton
class SettingsRepository @Inject constructor(private val store: DataStore<Preferences>) {
    /** Whether the first-run prompts for notifications and battery optimization have been shown. */
    val askedNotifications: Flow<Boolean> = store.data.map { it[ASKED_NOTIFICATIONS] == true }
    val askedBattery: Flow<Boolean> = store.data.map { it[ASKED_BATTERY] == true }

    suspend fun setAskedNotifications() = store.edit { it[ASKED_NOTIFICATIONS] = true }

    suspend fun setAskedBattery() = store.edit { it[ASKED_BATTERY] = true }

    private companion object {
        val ASKED_NOTIFICATIONS = booleanPreferencesKey("asked_notifications")
        val ASKED_BATTERY = booleanPreferencesKey("asked_battery")
    }
}
