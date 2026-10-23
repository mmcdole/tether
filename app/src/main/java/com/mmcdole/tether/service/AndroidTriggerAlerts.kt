package com.mmcdole.tether.service

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.mmcdole.tether.data.session.TriggerAlerts
import com.mmcdole.tether.data.world.World
import com.mmcdole.tether.mud.rules.NotifyRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidTriggerAlerts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notifications: SessionNotifications,
) : TriggerAlerts {
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }

    override fun vibrate() {
        val vibrator = vibrator?.takeIf { it.hasVibrator() } ?: return
        val effect = VibrationEffect.createOneShot(VIBRATE_MS, VibrationEffect.DEFAULT_AMPLITUDE)
        // As a notification, so it follows the notification vibration setting rather than touch feedback.
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
        }
    }

    override fun notify(world: World, request: NotifyRequest) = notifications.received(world, request)

    override fun clearNotifications(worldId: Long) = notifications.clearWorld(worldId)

    private companion object {
        const val VIBRATE_MS = 200L
    }
}
