package com.mmcdole.tether

import android.app.Application
import com.mmcdole.tether.service.SessionNotifications
import com.mmcdole.tether.service.SessionServiceLauncher
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class TetherApplication : Application() {
    @Inject lateinit var notifications: SessionNotifications
    @Inject lateinit var serviceLauncher: SessionServiceLauncher

    override fun onCreate() {
        super.onCreate()
        notifications.createChannels()
        serviceLauncher.start()
    }
}
