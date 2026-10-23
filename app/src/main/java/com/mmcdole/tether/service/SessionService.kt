package com.mmcdole.tether.service

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.mmcdole.tether.data.session.SessionManager
import com.mmcdole.tether.data.session.SessionState
import com.mmcdole.tether.data.settings.SessionOptions
import com.mmcdole.tether.data.settings.SessionOptionsSource
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while a session is active: the ongoing notification and the
 * Wi-Fi and CPU locks. The connection itself lives in [SessionManager].
 */
@AndroidEntryPoint
class SessionService : LifecycleService() {
    @Inject lateinit var session: SessionManager
    @Inject lateinit var notifications: SessionNotifications
    @Inject lateinit var options: SessionOptionsSource

    private var observing = false
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_DISCONNECT) {
            session.disconnect()
        } else if (!startForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        observe()
        // After the process dies there is no session to bring back.
        return START_NOT_STICKY
    }

    private fun startForeground(): Boolean {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        return try {
            ServiceCompat.startForeground(this, SessionNotifications.ONGOING_ID, notifications.ongoing(session.state.value), type)
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException on API 31+, when started from the background.
            Log.w(TAG, "Couldn't start in the foreground", e)
            false
        }
    }

    private fun observe() {
        if (observing) return
        observing = true
        lifecycleScope.launch {
            session.state.collect { state ->
                if (state == SessionState.Idle) {
                    ServiceCompat.stopForeground(this@SessionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    notifications.update(state)
                }
            }
        }
        lifecycleScope.launch {
            val active = session.state.map { it != SessionState.Idle }.distinctUntilChanged()
            combine(active, options.options) { isActive, current -> current.takeIf { isActive } }
                .collect(::holdLocks)
        }
    }

    /** Holds the locks [options] asks for, or none when null. */
    // The locks are held for as long as the session runs, which has no fixed end, and released here or in onDestroy.
    @SuppressLint("WakelockTimeout", "Wakelock")
    private fun holdLocks(options: SessionOptions?) {
        if (options?.wifiLock == true) {
            val lock = wifiLock ?: createWifiLock().also { wifiLock = it }
            if (!lock.isHeld) lock.acquire()
        } else {
            wifiLock?.takeIf { it.isHeld }?.release()
        }
        if (options?.cpuWakeLock == true) {
            val lock = wakeLock ?: getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tether:session")
                .also { it.setReferenceCounted(false); wakeLock = it }
            if (!lock.isHeld) lock.acquire()
        } else {
            wakeLock?.takeIf { it.isHeld }?.release()
        }
    }

    private fun createWifiLock(): WifiManager.WifiLock {
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        // HIGH_PERF on every level: it keeps Wi-Fi out of power save with the screen off. FULL does
        // nothing since API 29, and LOW_LATENCY only applies with the app on screen. API 34+
        // quietly turns HIGH_PERF into LOW_LATENCY, but the lock still does no harm there.
        @Suppress("DEPRECATION")
        return wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tether:session").apply {
            setReferenceCounted(false)
        }
    }

    override fun onDestroy() {
        holdLocks(null)
        super.onDestroy()
    }

    companion object {
        const val ACTION_DISCONNECT = "com.mmcdole.tether.action.DISCONNECT"
        private const val TAG = "SessionService"
    }
}
