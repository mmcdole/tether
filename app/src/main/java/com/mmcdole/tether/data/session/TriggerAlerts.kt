package com.mmcdole.tether.data.session

import com.mmcdole.tether.data.world.World
import com.mmcdole.tether.mud.rules.NotifyRequest

/** What a trigger can ask of the phone: a buzz or a notification. */
interface TriggerAlerts {
    fun vibrate()

    fun notify(world: World, request: NotifyRequest)

    /** Clears [worldId]'s message notifications, as when its session is opened. */
    fun clearNotifications(worldId: Long)
}
