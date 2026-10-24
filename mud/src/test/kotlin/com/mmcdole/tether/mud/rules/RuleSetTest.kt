package com.mmcdole.tether.mud.rules

import kotlin.test.Test
import kotlin.test.assertEquals

class RuleSetTest {
    private val world = Scope.World(1)

    @Test
    fun worldAliasWinsOverGlobalWithTheSameName() {
        val rules = RuleSet.build(
            worldId = 1,
            triggers = emptyList(),
            aliases = listOf(
                Alias(1, world, "k", "kill %1"),
                Alias(2, Scope.Global, "k", "kick %1"),
                Alias(3, Scope.Global, "eb", "eat bread"),
            ),
        )
        assertEquals("kill %1", rules.aliases.getValue("k").expansion)
        assertEquals("eat bread", rules.aliases.getValue("eb").expansion)
    }

    @Test
    fun otherWorldsAndDisabledRulesAreLeftOut() {
        val rules = RuleSet.build(
            worldId = 1,
            triggers = listOf(
                Trigger(1, Scope.World(2), "x"),
                Trigger(2, world, "y", enabled = false),
                Trigger(3, Scope.Global, "z"),
            ),
            aliases = listOf(
                Alias(1, Scope.World(2), "a", "x"),
                Alias(2, world, "b", "y", enabled = false),
                Alias(3, Scope.Global, "b", "global b"),
            ),
        )
        assertEquals(listOf(3L), rules.triggers.map { it.trigger.id })
        assertEquals(mapOf("b" to "global b"), rules.aliases.mapValues { it.value.expansion })
    }

    @Test
    fun disabledWorldAliasDoesNotHideTheGlobalOne() {
        val rules = RuleSet.build(1, emptyList(), listOf(Alias(1, world, "k", "a", enabled = false), Alias(2, Scope.Global, "k", "b")))
        assertEquals("b", rules.aliases.getValue("k").expansion)
    }

    @Test
    fun withNoWorldOnlyGlobalRulesApply() {
        val rules = RuleSet.build(null, listOf(Trigger(1, world, "x"), Trigger(2, Scope.Global, "x")), emptyList())
        assertEquals(listOf(2L), rules.triggers.map { it.trigger.id })
    }

    @Test
    fun triggersAreOrderedByPriorityThenWorldBeforeGlobal() {
        val rules = RuleSet.build(
            worldId = 1,
            triggers = listOf(
                Trigger(1, Scope.Global, "a", priority = 5),
                Trigger(2, world, "a", priority = 5),
                Trigger(3, Scope.Global, "a", priority = -1),
                Trigger(4, world, "a", priority = 10),
                Trigger(5, world, "a", priority = 5),
            ),
            aliases = emptyList(),
        )
        assertEquals(listOf(3L, 2L, 5L, 1L, 4L), rules.triggers.map { it.trigger.id })
    }

    @Test
    fun invalidPatternsAreReportedAndSkipped() {
        val bad = Trigger(1, world, "(", isRegex = true)
        val rules = RuleSet.build(1, listOf(bad, Trigger(2, world, "ok")), emptyList())
        assertEquals(listOf(2L), rules.triggers.map { it.trigger.id })
        assertEquals(listOf(bad), rules.invalid.map { it.trigger })
    }

    @Test
    fun gagsRunAsGagTriggersAndTimersPreferTheWorldOne() {
        val rules = RuleSet.build(
            1,
            emptyList(),
            emptyList(),
            gags = listOf(Gag(1, world, "spam"), Gag(2, Scope.Global, "off", enabled = false)),
            timers = listOf(
                Timer(3, Scope.Global, "heal", "cast heal", 1000),
                Timer(4, world, "heal", "cast big heal", 1000),
                Timer(5, Scope.Global, "save", "save", 60_000, enabled = false),
                Timer(6, Scope.World(2), "other", "x", 1000),
            ),
        )
        assertEquals(listOf("spam"), rules.triggers.filter { it.trigger.gag }.map { it.trigger.pattern })
        assertEquals(listOf(4L), rules.timers.map { it.id })
    }
}
