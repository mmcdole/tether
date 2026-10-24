package com.mmcdole.tether.data.rules

import app.cash.turbine.test
import com.mmcdole.tether.mud.rules.Alias
import com.mmcdole.tether.mud.rules.Gag
import com.mmcdole.tether.mud.rules.Scope
import com.mmcdole.tether.mud.rules.Timer
import com.mmcdole.tether.mud.rules.Trigger
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleRepositoryTest {
    private val dao = FakeRuleDao()
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC)
    private val repository = RuleRepository(dao, clock, Dispatchers.Unconfined)
    private val thorin = Scope.World(1)
    private val alt = Scope.World(2)

    @Test
    fun triggerRoundTripsEveryField() = runTest {
        val trigger = Trigger(
            id = 0, scope = thorin, pattern = "^%1 tells you: %2$", isRegex = true, enabled = false, group = "Chat",
            priority = -3, highlight = 0xFF0000, gag = true, send = "say hi", vibrate = true, notify = true,
            replyTemplate = "tell %1", replyRequiresUnlock = false,
        )
        val id = repository.save(trigger)
        assertEquals(trigger.copy(id = id), repository.getTrigger(id))
        assertNull(repository.getAlias(id))
        assertEquals(1_000L, dao.get(id)!!.createdAt)
    }

    @Test
    fun aliasMapsNameAndExpansionAndGlobalScope() = runTest {
        val id = repository.save(Alias(0, Scope.Global, "k", "kill %1", group = "Combat"))
        val row = dao.get(id)!!
        assertNull(row.worldId)
        assertEquals("k", row.pattern)
        assertEquals("kill %1", row.body)
        assertEquals(Alias(id, Scope.Global, "k", "kill %1", group = "Combat"), repository.getAlias(id))
    }

    @Test
    fun saveUpdatesAndKeepsCreatedAt() = runTest {
        val id = repository.save(Alias(0, thorin, "k", "kill %1"))
        dao.update(dao.get(id)!!.copy(createdAt = 5))
        repository.save(Alias(id, thorin, "k", "kick %1"))
        assertEquals("kick %1", dao.get(id)!!.body)
        assertEquals(5L, dao.get(id)!!.createdAt)
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun setEnabledAndDelete() = runTest {
        val id = repository.save(Alias(0, thorin, "k", "kill %1"))
        repository.setEnabled(id, false)
        assertFalse(repository.getAlias(id)!!.enabled)
        repository.delete(id)
        assertNull(repository.getAlias(id))
    }

    @Test
    fun ruleSetMergesGlobalAndThisWorldOnly() = runTest {
        repository.save(Alias(0, Scope.Global, "k", "kill %1"))
        repository.save(Alias(0, Scope.Global, "l", "look"))
        repository.save(Alias(0, thorin, "k", "kick %1"))
        repository.save(Alias(0, alt, "x", "nope"))
        repository.save(Trigger(0, Scope.Global, "global line", gag = true))
        repository.save(Trigger(0, thorin, "world line", gag = true))
        repository.save(Trigger(0, alt, "other line", gag = true))

        val set = repository.observeRuleSet(1).first()
        assertEquals(setOf("k", "l"), set.aliases.keys)
        assertEquals("kick %1", set.aliases.getValue("k").expansion)
        // World before global at equal priority.
        assertEquals(listOf("world line", "global line"), set.triggers.map { it.trigger.pattern })

        val globalOnly = repository.observeRuleSet(null).first()
        assertEquals("kill %1", globalOnly.aliases.getValue("k").expansion)
        assertEquals(listOf("global line"), globalOnly.triggers.map { it.trigger.pattern })
    }

    @Test
    fun ruleSetRebuildsWhenEitherScopeChanges() = runTest {
        repository.observeRuleSet(1).test {
            assertTrue(awaitItem().aliases.isEmpty())
            repository.addAlias(Scope.Global, "k", "kill %1")
            assertEquals("kill %1", awaitItem().aliases.getValue("k").expansion)
            repository.addAlias(thorin, "k", "kick %1")
            assertEquals("kick %1", awaitItem().aliases.getValue("k").expansion)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun ruleListsSplitByScopeWithCounts() = runTest {
        repository.save(Alias(0, Scope.Global, "k", "kill %1"))
        repository.save(Alias(0, thorin, "l", "look"))
        repository.save(Alias(0, alt, "x", "nope"))
        repository.save(Trigger(0, thorin, "a", gag = true))

        val lists = repository.observeRules(1).first()
        assertEquals(listOf("l"), lists.worldAliases.map { it.name })
        assertEquals(listOf("k"), lists.globalAliases.map { it.name })
        assertEquals(listOf("a"), lists.worldTriggers.map { it.pattern })
        assertEquals(1, lists.triggerCount)
        assertEquals(2, lists.aliasCount)

        val global = repository.observeRules(null).first()
        assertEquals(0, global.triggerCount)
        assertEquals(listOf("k"), global.globalAliases.map { it.name })
    }

    @Test
    fun addAliasReplacesSameNameInSameScopeOnly() = runTest {
        val global = repository.addAlias(Scope.Global, "k", "kill %1")
        val first = repository.addAlias(thorin, "k", "kill %1")
        repository.setEnabled(first, false)
        val second = repository.addAlias(thorin, "k", "kick %1")

        assertEquals(first, second)
        assertEquals(Alias(first, thorin, "k", "kick %1"), repository.getAlias(first))
        assertEquals("kill %1", repository.getAlias(global)!!.expansion)
        assertEquals(2, dao.rows.value.size)
    }

    @Test
    fun addActionReplacesTheCommandAndAddGagIsNotDuplicated() = runTest {
        val action = repository.addAction(thorin, "%1 arrives", "wave %1")
        assertEquals(action, repository.addAction(thorin, "%1 arrives", "bow %1"))
        assertEquals("bow %1", repository.getTrigger(action)!!.send)

        val gag = repository.addGag(thorin, "%1 arrives")
        assertTrue(gag != action)
        assertEquals(gag, repository.addGag(thorin, "%1 arrives"))
        assertEquals(Gag(gag, thorin, "%1 arrives"), repository.getGag(gag))
        assertEquals(2, dao.rows.value.size)
    }

    @Test
    fun removeAliasPrefersWorldThenGlobal() = runTest {
        repository.addAlias(Scope.Global, "k", "kill %1")
        repository.addAlias(thorin, "k", "kick %1")

        assertEquals(Removal.Removed(thorin), repository.removeAlias(1, "k"))
        assertEquals(Removal.Removed(Scope.Global), repository.removeAlias(1, "k"))
        assertEquals(Removal.NotFound, repository.removeAlias(1, "k"))
    }

    @Test
    fun removeAliasDoesNotTouchOtherWorlds() = runTest {
        repository.addAlias(alt, "k", "kill %1")
        assertEquals(Removal.NotFound, repository.removeAlias(1, "k"))
        assertEquals(Removal.NotFound, repository.removeAlias(null, "k"))
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun removeActionAndGagOnlyTakeOffThatAction() = runTest {
        repository.addAction(Scope.Global, "%1 arrives", "wave %1")
        val both = repository.save(Trigger(0, thorin, "%1 arrives", gag = true, send = "bow %1"))

        // The world trigger keeps its gag; the global action is untouched.
        assertEquals(Removal.Removed(thorin), repository.removeAction(1, "%1 arrives"))
        assertEquals(Trigger(both, thorin, "%1 arrives", gag = true), repository.getTrigger(both))

        assertEquals(Removal.Removed(thorin), repository.removeGag(1, "%1 arrives"))
        assertNull(repository.getTrigger(both))

        assertEquals(Removal.NotFound, repository.removeGag(1, "%1 arrives"))
        assertEquals(Removal.Removed(Scope.Global), repository.removeAction(1, "%1 arrives"))
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun removeKeepsTriggersThatStillDoSomething() = runTest {
        val id = repository.save(Trigger(0, thorin, "tells you", gag = true, notify = true))
        assertEquals(Removal.Removed(thorin), repository.removeGag(1, "tells you"))
        assertEquals(Trigger(id, thorin, "tells you", notify = true), repository.getTrigger(id))
        assertEquals(Removal.NotFound, repository.removeAction(1, "tells you"))
    }

    @Test
    fun addTimerReplacesByNameAndTurnsOn() = runTest {
        val id = repository.addTimer(thorin, "heal", "cast heal", 1000)
        assertEquals(Removal.Removed(thorin), repository.setTimerEnabled(1, "heal", false))
        assertEquals(id, repository.addTimer(thorin, "heal", "cast big heal", 2000))
        assertEquals(Timer(id, thorin, "heal", "cast big heal", 2000), repository.getTimer(id))

        val global = repository.addTimer(Scope.Global, "save", "save", 60_000)
        assertEquals(Removal.Removed(Scope.Global), repository.setTimerEnabled(1, "save", false))
        assertFalse(repository.getTimer(global)!!.enabled)
        assertEquals(Removal.NotFound, repository.setTimerEnabled(1, "nope", true))
    }

    @Test
    fun ungagRemovesGagsBeforeTakingTheGagOffTriggers() = runTest {
        val gag = repository.addGag(thorin, "spam")
        val trigger = repository.save(Trigger(0, thorin, "spam", gag = true, notify = true))
        assertEquals(Removal.Removed(thorin), repository.removeGag(1, "spam"))
        assertNull(repository.getGag(gag))
        assertTrue(repository.getTrigger(trigger)!!.gag)
        assertEquals(Removal.Removed(thorin), repository.removeGag(1, "spam"))
        assertFalse(repository.getTrigger(trigger)!!.gag)
    }
}
