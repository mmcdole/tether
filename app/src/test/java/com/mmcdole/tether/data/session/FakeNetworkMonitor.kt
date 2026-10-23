package com.mmcdole.tether.data.session

import com.mmcdole.tether.data.settings.SessionOptions
import com.mmcdole.tether.data.settings.SessionOptionsSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.toJavaDuration

class FakeNetworkMonitor : NetworkMonitor {
    @Volatile var current: Long? = 1

    private val _events = MutableSharedFlow<NetworkEvent>(extraBufferCapacity = 16)
    override val events = _events

    override fun defaultNetwork(): Long? = current

    fun emit(event: NetworkEvent) = check(_events.tryEmit(event))
}

class FixedSessionOptions(value: SessionOptions = SessionOptions()) : SessionOptionsSource {
    override val options: StateFlow<SessionOptions> = MutableStateFlow(value)
}

/** Real time plus whatever [advance] has added, for code that compares timestamps. */
class AdvancingClock : Clock() {
    @Volatile private var offset = java.time.Duration.ZERO

    fun advance(by: Duration) {
        offset += by.toJavaDuration()
    }

    override fun instant(): Instant = Instant.now().plus(offset)

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}
