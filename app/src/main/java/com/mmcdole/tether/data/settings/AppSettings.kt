package com.mmcdole.tether.data.settings

import com.mmcdole.tether.data.session.Scrollback

/** Everything on the Settings screen. */
data class AppSettings(
    val textSize: Int = DEFAULT_TEXT_SIZE,
    val hideStatusBar: Boolean = false,
    val autocorrect: Boolean = false,
    val keepKeyboardOpen: Boolean = true,
    val autoReconnect: Boolean = true,
    val wifiLock: Boolean = true,
    val cpuWakeLock: Boolean = false,
    val scrollbackLines: Int = Scrollback.DEFAULT_CAPACITY,
    val saveLogs: Boolean = false,
) {
    val sessionOptions: SessionOptions
        get() = SessionOptions(autoReconnect, wifiLock, cpuWakeLock, scrollbackLines, saveLogs)

    companion object {
        const val MIN_TEXT_SIZE = 8
        const val MAX_TEXT_SIZE = 20
        const val DEFAULT_TEXT_SIZE = 13
        val SCROLLBACK_CHOICES = listOf(1_000, 5_000, 10_000, 20_000)
    }
}
