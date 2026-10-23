package com.mmcdole.tether.service

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.mmcdole.tether.data.session.SessionManager
import com.mmcdole.tether.data.session.SessionState
import com.mmcdole.tether.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Starts [SessionService] whenever a session becomes active. The service stops itself. */
@Singleton
class SessionServiceLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: SessionManager,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            session.state.map { it != SessionState.Idle }.distinctUntilChanged().collect { active ->
                if (active) startService()
            }
        }
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, SessionService::class.java))
        } catch (e: IllegalStateException) {
            // Sessions start from the app's own screen, so this means the app was already backgrounded.
            if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "Not allowed to start the session service from the background", e)
            } else {
                Log.w(TAG, "Couldn't start the session service", e)
            }
        }
    }

    private companion object {
        const val TAG = "SessionServiceLauncher"
    }
}
