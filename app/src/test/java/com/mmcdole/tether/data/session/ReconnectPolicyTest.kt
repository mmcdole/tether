package com.mmcdole.tether.data.session

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectPolicyTest {
    private val policy = ReconnectPolicy()

    @Test
    fun backsOffThenCapsAtOneMinute() {
        val delays = (1..8).map { policy.delayFor(it).inWholeSeconds }
        assertEquals(listOf(1L, 2, 5, 10, 30, 60, 60, 60), delays)
    }

    @Test
    fun waitsTheFullDelay() = runTest {
        val wake = Channel<Unit>(Channel.CONFLATED)
        val wait = launch { policy.await(3, wake) }
        advanceTimeBy(5.seconds - 1.seconds / 1000)
        runCurrent()
        assertTrue(wait.isActive)
        advanceTimeBy(1.seconds / 1000)
        runCurrent()
        assertFalse(wait.isActive)
    }

    @Test
    fun networkComingBackCutsTheWaitShort() = runTest {
        val wake = Channel<Unit>(Channel.CONFLATED)
        val wait = launch { policy.await(6, wake) }
        advanceTimeBy(10.seconds)
        assertTrue(wait.isActive)
        wake.send(Unit)
        runCurrent()
        assertFalse(wait.isActive)
        assertEquals(10_000L, testScheduler.currentTime)
    }

    @Test
    fun wakeFromBeforeTheWaitIsIgnored() = runTest {
        val wake = Channel<Unit>(Channel.CONFLATED)
        wake.send(Unit)
        val wait = launch { policy.await(1, wake) }
        runCurrent()
        assertTrue(wait.isActive)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertFalse(wait.isActive)
    }
}
