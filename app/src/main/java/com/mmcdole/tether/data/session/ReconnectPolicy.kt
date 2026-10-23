package com.mmcdole.tether.data.session

import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long to wait before each reconnect attempt, and how many to make. Attempts count
 * from 1; the last delay repeats.
 */
class ReconnectPolicy(private val delays: List<Duration>, val maxAttempts: Int = MAX_ATTEMPTS) {
    @Inject constructor() : this(DEFAULT)

    init {
        require(delays.isNotEmpty())
    }

    fun delayFor(attempt: Int): Duration = delays[(attempt - 1).coerceIn(0, delays.lastIndex)]

    /** Waits before [attempt], or less if [wake] fires first (a network came back). */
    suspend fun await(attempt: Int, wake: ReceiveChannel<Unit>) {
        // A wake from before the wait started is stale.
        wake.tryReceive()
        withTimeoutOrNull(delayFor(attempt)) { wake.receive() }
    }

    companion object {
        const val MAX_ATTEMPTS = 10
        val DEFAULT = listOf(1.seconds, 2.seconds, 5.seconds, 10.seconds, 30.seconds, 60.seconds)
    }
}
