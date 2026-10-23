package com.mmcdole.tether.ui.worlds

import com.mmcdole.tether.data.rules.FakeRuleDao
import com.mmcdole.tether.data.rules.RuleRepository
import com.mmcdole.tether.data.session.FakeNetworkMonitor
import com.mmcdole.tether.data.session.FakeTriggerAlerts
import com.mmcdole.tether.data.session.FixedSessionOptions
import com.mmcdole.tether.data.session.ReconnectPolicy
import com.mmcdole.tether.data.session.SessionManager
import com.mmcdole.tether.data.world.FakeWorldDao
import com.mmcdole.tether.data.world.World
import com.mmcdole.tether.data.world.WorldRepository
import com.mmcdole.tether.data.world.testSecrets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorldsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val dao = FakeWorldDao()
    private val clock = Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC)
    private val repository = WorldRepository(dao, testSecrets(), clock, dispatcher)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun rowsShowAddressAndLastPlayed() = runTest(dispatcher) {
        val alt = repository.save(World(name = "VikingMUD – Alt", host = "host.example", port = 2001, tls = true))
        repository.save(World(name = "Test MUD", host = "localhost", port = 7680))
        dao.setLastConnected(alt, clock.instant().minus(Duration.ofDays(1)).toEpochMilli())

        val rules = RuleRepository(FakeRuleDao(), clock, dispatcher)
        val session = SessionManager(
            repository, rules, { _, _, _ -> error("no network") }, FakeNetworkMonitor(), ReconnectPolicy(), FixedSessionOptions(),
            FakeTriggerAlerts(), backgroundScope, dispatcher, StandardTestDispatcher(testScheduler), clock,
        )
        val vm = WorldsViewModel(repository, rules, session, clock)
        backgroundScope.launch { vm.state.collect {} }

        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals(
            listOf(
                WorldRow(alt, "VikingMUD – Alt", "host.example : 2001 · TLS", "Yesterday"),
                WorldRow(alt + 1, "Test MUD", "localhost : 7680", null),
            ),
            state.worlds,
        )
    }
}
