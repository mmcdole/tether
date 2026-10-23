package com.mmcdole.tether.data.settings

import com.mmcdole.tether.data.session.Scrollback
import com.mmcdole.tether.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class SessionOptions(
    val autoReconnect: Boolean = true,
    val wifiLock: Boolean = true,
    val cpuWakeLock: Boolean = false,
    val scrollbackLines: Int = Scrollback.DEFAULT_CAPACITY,
    val saveLogs: Boolean = false,
)

/** Where session options come from. */
interface SessionOptionsSource {
    val options: StateFlow<SessionOptions>
}

/** The session options in [SettingsRepository], updated as they change. */
@Singleton
class StoredSessionOptions @Inject constructor(
    settings: SettingsRepository,
    @ApplicationScope scope: CoroutineScope,
) : SessionOptionsSource {
    override val options: StateFlow<SessionOptions> = settings.settings
        .map { it.sessionOptions }
        .stateIn(scope, SharingStarted.Eagerly, SessionOptions())
}
