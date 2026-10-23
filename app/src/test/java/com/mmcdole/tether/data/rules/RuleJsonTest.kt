package com.mmcdole.tether.data.rules

import com.mmcdole.tether.data.db.RuleKind
import com.mmcdole.tether.data.db.WorldEntity
import com.mmcdole.tether.data.world.FakeWorldDao
import com.mmcdole.tether.mud.rules.Alias
import com.mmcdole.tether.mud.rules.Scope
import com.mmcdole.tether.mud.rules.Trigger
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuleJsonTest {
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC)

    private class Device(clock: Clock) {
        val worlds = FakeWorldDao()
        val rules = FakeRuleDao()
        val repository = RuleRepository(rules, clock, Dispatchers.Unconfined)
        val json = RuleJson(rules, worlds, clock)

        suspend fun addWorlds(vararg names: String) = names.map {
            worlds.insert(WorldEntity(name = it, host = "h", port = 1, tls = false, username = null, encryptedPassword = null, lastConnectedAt = null, createdAt = 0))
        }

        /** Rules with ids and world ids replaced by world names, for comparing devices. */
        suspend fun snapshot(): List<Any> {
            val names = worlds.observeAll().first().associate { it.id to it.name }
            fun name(scope: Scope) = (scope as? Scope.World)?.let { names[it.worldId] }
            return rules.getAll().map { row ->
                if (row.kind == RuleKind.Alias) {
                    row.toAlias().let { name(it.scope) to it.copy(id = 0, scope = Scope.Global) }
                } else {
                    row.toTrigger().let { name(it.scope) to it.copy(id = 0, scope = Scope.Global) }
                }
            }
        }
    }

    @Test
    fun roundTripsEveryFieldByWorldName() = runTest {
        val source = Device(clock)
        val (thorin, alt) = source.addWorlds("Viking – Thorin", "Viking – Alt")
        source.repository.save(
            Trigger(
                0, Scope.World(thorin), "^(\\w+) tells you: (.*)$", isRegex = true, enabled = false, group = "Chat",
                priority = 2, highlight = 0x00FF00, gag = true, send = "say hi", vibrate = true, notify = true,
                replyTemplate = "tell %1", replyRequiresUnlock = false,
            ),
        )
        source.repository.save(Trigger(0, Scope.Global, "You are hungry", send = "eat bread"))
        source.repository.save(Alias(0, Scope.World(alt), "k", "kill %1", enabled = false, group = "Combat"))
        source.repository.save(Alias(0, Scope.Global, "l", "look"))
        val exported = source.json.export()

        // Different ids on the other device.
        val target = Device(clock)
        target.addWorlds("Unrelated", "Viking – Alt", "Viking – Thorin")
        assertEquals(ImportSummary(4, 0, 0, emptyList()), target.json.import(exported))
        assertEquals(source.snapshot().toSet(), target.snapshot().toSet())
    }

    @Test
    fun globalRulesHaveNullWorldAndDefaultsAreLeftOut() = runTest {
        val device = Device(clock)
        device.repository.save(Alias(0, Scope.Global, "l", "look"))
        val alias = Json.parseToJsonElement(device.json.export()).jsonObject.getValue("aliases").jsonArray.single().jsonObject
        assertEquals(JsonNull, alias.getValue("world"))
        assertEquals(setOf("world", "name", "expansion"), alias.keys)
    }

    @Test
    fun unknownWorldsAreSkippedAndCounted() = runTest {
        val source = Device(clock)
        val (thorin, alt) = source.addWorlds("Viking – Thorin", "Viking – Alt")
        source.repository.save(Alias(0, Scope.World(thorin), "k", "kill %1"))
        source.repository.save(Trigger(0, Scope.World(alt), "x", gag = true))
        source.repository.save(Alias(0, Scope.World(alt), "y", "yell"))
        source.repository.save(Alias(0, Scope.Global, "l", "look"))

        val target = Device(clock)
        target.addWorlds("Viking – Thorin")
        assertEquals(ImportSummary(2, 2, 0, listOf("Viking – Alt")), target.json.import(source.json.export()))
        assertEquals(1, target.worlds.observeAll().first().size)
    }

    @Test
    fun reimportSkipsDuplicatesAndReplacesAliasesByName() = runTest {
        val device = Device(clock)
        device.repository.save(Alias(0, Scope.Global, "k", "kill %1"))
        device.repository.save(Trigger(0, Scope.Global, "x", gag = true))
        val exported = device.json.export()
        assertEquals(ImportSummary(0, 0, 2, emptyList()), device.json.import(exported))

        device.repository.addAlias(Scope.Global, "k", "kick %1")
        assertEquals(ImportSummary(1, 0, 1, emptyList()), device.json.import(exported))
        assertEquals(listOf("kill %1"), device.rules.getAll().filter { it.pattern == "k" }.map { it.body })
        assertEquals(2, device.rules.getAll().size)
    }

    @Test
    fun rejectsNonRulesJson() = runTest {
        val device = Device(clock)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { device.json.import("[1, 2]") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { device.json.import("""{"version": 99}""") } }
    }
}
