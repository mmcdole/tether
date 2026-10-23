package com.mmcdole.tether.data.session

import com.mmcdole.tether.data.world.World
import java.time.Instant

sealed interface SessionState {
    data object Idle : SessionState
    data class Connecting(val world: World) : SessionState
    data class Connected(val world: World, val since: Instant) : SessionState

    /** The connection dropped and [attempt] is the next try, waiting or under way. */
    data class Reconnecting(val world: World, val attempt: Int) : SessionState
}

/** The world being played, or null when idle. */
val SessionState.activeWorld: World?
    get() = when (this) {
        SessionState.Idle -> null
        is SessionState.Connecting -> world
        is SessionState.Connected -> world
        is SessionState.Reconnecting -> world
    }
