package com.mmcdole.tether.data.session

import com.mmcdole.tether.data.world.World
import com.mmcdole.tether.mud.rules.NotifyRequest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class FakeTriggerAlerts : TriggerAlerts {
    val vibrations = AtomicInteger()
    val notifications = CopyOnWriteArrayList<Pair<World, NotifyRequest>>()

    override fun vibrate() {
        vibrations.incrementAndGet()
    }

    override fun notify(world: World, request: NotifyRequest) {
        notifications += world to request
    }

    val cleared = CopyOnWriteArrayList<Long>()

    override fun clearNotifications(worldId: Long) {
        cleared += worldId
    }
}
