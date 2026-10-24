package com.mmcdole.tether.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }
    }
    private val repository by lazy { SettingsRepository(store) }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun defaultsWhenNothingIsStored() = runBlocking<Unit> {
        val settings = repository.settings.first()
        assertEquals(AppSettings(), settings)
        assertEquals(13, settings.textSize)
        assertEquals(false, settings.hideStatusBar)
        assertEquals(false, settings.autocorrect)
        assertEquals(true, settings.keepKeyboardOpen)
        assertEquals(true, settings.autoReconnect)
        assertEquals(true, settings.wifiLock)
        assertEquals(true, settings.cpuWakeLock)
        assertEquals(5_000, settings.scrollbackLines)
    }

    @Test
    fun everySettingIsStored() = runBlocking<Unit> {
        repository.setTextSize(17)
        repository.setHideStatusBar(true)
        repository.setAutocorrect(true)
        repository.setKeepKeyboardOpen(false)
        repository.setAutoReconnect(false)
        repository.setWifiLock(false)
        repository.setCpuWakeLock(false)
        repository.setScrollbackLines(20_000)
        val expected = AppSettings(
            textSize = 17,
            hideStatusBar = true,
            autocorrect = true,
            keepKeyboardOpen = false,
            autoReconnect = false,
            wifiLock = false,
            cpuWakeLock = false,
            scrollbackLines = 20_000,
        )
        assertEquals(expected, repository.settings.first())
        assertEquals(SessionOptions(autoReconnect = false, wifiLock = false, cpuWakeLock = false, scrollbackLines = 20_000), expected.sessionOptions)
    }

    @Test
    fun textSizeIsKeptInRange() = runBlocking<Unit> {
        repository.setTextSize(40)
        assertEquals(20, repository.settings.first().textSize)
        repository.setTextSize(2)
        assertEquals(8, repository.settings.first().textSize)
    }

    @Test
    fun storedValuesOutsideTheChoicesFallBackToDefaults() = runBlocking<Unit> {
        store.edit {
            it[intPreferencesKey("scrollback_lines")] = 123
            it[intPreferencesKey("text_size")] = 99
        }
        val settings = repository.settings.first()
        assertEquals(5_000, settings.scrollbackLines)
        assertEquals(20, settings.textSize)
    }

    @Test
    fun sessionOptionsFollowTheStoredSettings() = runBlocking<Unit> {
        val source = StoredSessionOptions(repository, scope)
        withTimeout(2_000) { source.options.first { it == SessionOptions() } }
        repository.setWifiLock(false)
        repository.setScrollbackLines(1_000)
        withTimeout(2_000) { source.options.first { !it.wifiLock && it.scrollbackLines == 1_000 } }
        repository.setCpuWakeLock(false)
        withTimeout(2_000) { source.options.first { !it.cpuWakeLock } }
    }
}
