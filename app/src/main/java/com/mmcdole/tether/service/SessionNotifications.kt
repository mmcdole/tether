package com.mmcdole.tether.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mmcdole.tether.MainActivity
import com.mmcdole.tether.R
import com.mmcdole.tether.data.session.SessionState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionNotifications @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        manager.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_SESSION, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Session")
                    .setShowBadge(false)
                    .build(),
                // Trigger notifications, from phase 8.
                NotificationChannelCompat.Builder(CHANNEL_MESSAGES, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName("Messages")
                    .build(),
            ),
        )
    }

    fun ongoing(state: SessionState): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_SESSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnect = PendingIntent.getService(
            context,
            0,
            Intent(context, SessionService::class.java).setAction(SessionService.ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(state.title())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Open", open)
            .addAction(0, "Disconnect", disconnect)
        // The system ticks the uptime, so nothing has to post every second.
        if (state is SessionState.Connected) {
            builder.setWhen(state.since.toEpochMilli()).setShowWhen(true).setUsesChronometer(true)
        } else {
            builder.setShowWhen(false)
        }
        return builder.build()
    }

    fun update(state: SessionState) {
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) manager.notify(ONGOING_ID, ongoing(state))
    }

    private fun SessionState.title(): String = when (this) {
        SessionState.Idle -> "Disconnected"
        is SessionState.Connecting -> "Connecting to ${world.name}"
        is SessionState.Connected -> "Connected to ${world.name}"
        is SessionState.Reconnecting -> "Reconnecting to ${world.name}"
    }

    companion object {
        const val CHANNEL_SESSION = "session"
        const val CHANNEL_MESSAGES = "messages"
        const val ONGOING_ID = 1
    }
}
