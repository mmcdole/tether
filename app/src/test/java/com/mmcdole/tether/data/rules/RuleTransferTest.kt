package com.mmcdole.tether.data.rules

import com.mmcdole.tether.mud.rules.Alias
import com.mmcdole.tether.mud.rules.Scope
import com.mmcdole.tether.mud.rules.Trigger
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleTransferTest {
    private val dao = FakeRuleDao()
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC)
    private val repository = RuleRepository(dao, clock, Dispatchers.Unconfined)
    private val transfer = RuleTransfer(dao, clock)
    private val thorin = Scope.World(1)
    private val alt = Scope.World(2)
    private val third = Scope.World(3)

    private fun triggersIn(scope: Scope) =
        dao.rows.value.values.filter { it.worldId == scope.worldId }.map { it.toTrigger() }

    @Test
    fun copyMakesIndependentRowsAndSkipsOwnScope() = runTest {
        val id = repository.save(Trigger(0, thorin, "%1 tells you: %2", notify = true, group = "Chat"))
        val result = transfer.copy(id, listOf(thorin, alt, Scope.Global))!!

        assertEquals(TransferResult(listOf(alt, Scope.Global), emptyList(), originalDeleted = false), result)
        assertEquals(3, dao.rows.value.size)
        val copy = triggersIn(alt).single()
        assertEquals(Trigger(copy.id, alt, "%1 tells you: %2", notify = true, group = "Chat"), copy)

        repository.save(copy.copy(pattern = "changed"))
        assertEquals("%1 tells you: %2", repository.getTrigger(id)!!.pattern)
    }

    @Test
    fun triggersNeverConflict() = runTest {
        val id = repository.save(Trigger(0, thorin, "x", gag = true))
        repository.save(Trigger(0, alt, "x", gag = true))
        assertEquals(emptyList<Scope>(), transfer.conflicts(id, listOf(alt)))
        transfer.copy(id, listOf(alt), OnConflict.Skip)
        assertEquals(2, triggersIn(alt).size)
    }

    @Test
    fun aliasConflictsAreReportedPerDestination() = runTest {
        val id = repository.addAlias(thorin, "k", "kill %1")
        repository.addAlias(alt, "k", "kick %1")
        repository.addAlias(third, "l", "look")
        assertEquals(listOf(alt), transfer.conflicts(id, listOf(thorin, alt, third, Scope.Global)))
    }

    @Test
    fun replaceOverwritesTheConflictingAlias() = runTest {
        val id = repository.addAlias(thorin, "k", "kill %1")
        val old = repository.addAlias(alt, "k", "kick %1")
        val result = transfer.copy(id, listOf(alt, third), OnConflict.Replace)!!

        assertEquals(listOf(alt, third), result.copiedTo)
        assertNull(repository.getAlias(old))
        assertEquals(listOf("kill %1"), dao.rows.value.values.filter { it.worldId == 2L }.map { it.body })
    }

    @Test
    fun skipLeavesConflictingDestinations() = runTest {
        val id = repository.addAlias(thorin, "k", "kill %1")
        val old = repository.addAlias(alt, "k", "kick %1")
        val result = transfer.copy(id, listOf(alt, third), OnConflict.Skip)!!

        assertEquals(TransferResult(listOf(third), listOf(alt), originalDeleted = false), result)
        assertEquals("kick %1", repository.getAlias(old)!!.expansion)
    }

    @Test
    fun moveCopiesThenDeletesTheOriginal() = runTest {
        val id = repository.addAlias(thorin, "k", "kill %1")
        val result = transfer.move(id, listOf(Scope.Global, alt))!!

        assertEquals(TransferResult(listOf(Scope.Global, alt), emptyList(), originalDeleted = true), result)
        assertNull(repository.getAlias(id))
        assertEquals(setOf(null, 2L), dao.rows.value.values.map { it.worldId }.toSet())
    }

    @Test
    fun moveKeepsTheOriginalWhenNothingWasCopied() = runTest {
        val id = repository.addAlias(thorin, "k", "kill %1")
        repository.addAlias(alt, "k", "kick %1")
        val result = transfer.move(id, listOf(alt, thorin), OnConflict.Skip)!!

        assertEquals(TransferResult(emptyList(), listOf(alt), originalDeleted = false), result)
        assertEquals(Alias(id, thorin, "k", "kill %1"), repository.getAlias(id))
    }

    @Test
    fun missingRuleReturnsNull() = runTest {
        assertNull(transfer.copy(42, listOf(alt)))
        assertNull(transfer.move(42, listOf(alt)))
    }
}
