package com.mmcdole.tether.data.settings

import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SessionOptions(
    val autoReconnect: Boolean = true,
    val wifiLock: Boolean = true,
    val cpuWakeLock: Boolean = false,
)

/** Where session options come from. */
interface SessionOptionsSource {
    val options: StateFlow<SessionOptions>
}

/** The defaults, until phase 5 stores them in [SettingsRepository] and binds that instead. */
class DefaultSessionOptions @Inject constructor() : SessionOptionsSource {
    override val options: StateFlow<SessionOptions> = MutableStateFlow(SessionOptions()).asStateFlow()
}
