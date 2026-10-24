package com.mmcdole.tether.mud.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TriggerEngineTest {
    private val world = Scope.World(1)

    private fun run(line: String, vararg triggers: Trigger) =
        TriggerEngine.run(RuleSet.build(1, triggers.toList(), emptyList()), line, "Viking")

    @Test
    fun noMatchIsNone() {
        assertEquals(TriggerResult.None, run("hello", Trigger(1, world, "bye", gag = true)))
    }

    @Test
    fun highlightAndVibrate() {
        val result = run("The town guard slashes you.", Trigger(1, world, "The %1 slashes you.", highlight = 0xFF0000, vibrate = true))
        assertEquals(0xFF0000, result.highlight)
        assertTrue(result.vibrate)
        assertFalse(result.gag)
    }

    @Test
    fun sendIsSplitAndExpanded() {
        val result = run("You are hungry.", Trigger(1, world, "You are %1.", send = "eat bread;say I was %1"))
        assertEquals(listOf("eat bread", "say I was hungry"), result.commands)
    }

    @Test
    fun semicolonInMudTextDoesNotSplitTheCommand() {
        val result = run("Bob tells you: hi;drop all", Trigger(1, world, "%1 tells you: %2", send = "say %2"))
        assertEquals(listOf("say hi;drop all"), result.commands)
    }

    @Test
    fun gagStillRunsOtherTriggersOnTheLine() {
        val result = run(
            "The fountain gurgles.",
            Trigger(1, world, "fountain", gag = true, send = "smile"),
            Trigger(2, Scope.Global, "gurgles", send = "listen", vibrate = true),
        )
        assertTrue(result.gag)
        assertEquals(listOf("smile", "listen"), result.commands)
        assertTrue(result.vibrate)
    }

    @Test
    fun globalAndWorldTriggersBothFireWorldFirst() {
        val result = run(
            "You are hungry.",
            Trigger(1, Scope.Global, "hungry", send = "global"),
            Trigger(2, world, "hungry", send = "world"),
        )
        assertEquals(listOf("world", "global"), result.commands)
    }

    @Test
    fun priorityOrdersCommandsAndPicksTheHighlight() {
        val result = run(
            "x",
            Trigger(1, world, "x", priority = 2, send = "second", highlight = 0x00FF00),
            Trigger(2, Scope.Global, "x", priority = 1, send = "first", highlight = 0xFF0000),
            Trigger(3, world, "x", priority = 0, send = "zeroth"),
        )
        assertEquals(listOf("zeroth", "first", "second"), result.commands)
        assertEquals(0xFF0000, result.highlight)
    }

    @Test
    fun disabledTriggersDoNotFire() {
        assertEquals(TriggerResult.None, run("x", Trigger(1, world, "x", send = "y", enabled = false)))
    }

    @Test
    fun notifyWithSenderAndMessage() {
        val result = run(
            "Hagbard tells you: wanna group for the troll?",
            Trigger(7, Scope.Global, "%1 tells you: %2", notify = true, replyTemplate = "tell %1"),
        )
        assertEquals(listOf(NotifyRequest(7, "Hagbard", "wanna group for the troll?", "tell Hagbard", true)), result.notifications)
    }

    @Test
    fun notifyWithoutTwoCapturesUsesWorldNameAndLine() {
        val result = run("You are hungry.", Trigger(1, world, "You are %1.", notify = true, replyRequiresUnlock = false))
        assertEquals(listOf(NotifyRequest(1, "Viking", "You are hungry.", null, false)), result.notifications)
    }

    @Test
    fun notifyUsesTheLastCaptureAsMessage() {
        val result = run("[ooc] Bob: hi all", Trigger(1, world, "^\\[(\\w+)\\] (\\w+): (.*)", isRegex = true, notify = true))
        assertEquals("ooc", result.notifications.single().title)
        assertEquals("hi all", result.notifications.single().message)
    }

    @Test
    fun regexTriggersSkipLongLinesButPlainOnesRun() {
        val long = "a".repeat(TriggerEngine.MAX_REGEX_LINE) + " needle"
        val result = run(
            long,
            Trigger(1, world, "needle", isRegex = true, send = "regex"),
            Trigger(2, world, "needle", send = "plain"),
        )
        assertEquals(listOf("plain"), result.commands)
    }

    @Test
    fun regexTriggersRunAtExactlyTheLimit() {
        val line = "a".repeat(TriggerEngine.MAX_REGEX_LINE - 1) + "b"
        assertEquals(listOf("y"), run(line, Trigger(1, world, "b$", isRegex = true, send = "y")).commands)
    }

    @Test
    fun matchWithNoActionsIsNone() {
        val result = run("x", Trigger(1, world, "x"))
        assertNull(result.highlight)
        assertTrue(result.isEmpty)
    }

    @Test
    fun chatCopiesFromWhereThePatternMatched() {
        val prompt = "HP: 346 SP: 274 "
        val result = run("$prompt[Party]: Soblak says: hi", Trigger(1, Scope.Global, "[Party]: %1", chat = true))
        assertEquals(TriggerResult(chat = ChatCopy(prompt.length)), result)
        assertEquals(TriggerResult.None, run("Tick.", Trigger(1, Scope.Global, "[Party]: %1", chat = true)))
    }

    @Test
    fun chatTextUsesCapturesAndTheFirstChatTriggerWins() {
        val tell = Trigger(1, Scope.Global, "(\\w+) tells you: (.*)", isRegex = true, chat = true, chatText = "%1: %2")
        val other = Trigger(2, Scope.Global, "tells", chat = true)
        val result = run("HP: 346 Storgrim tells you: Certainly", tell, other)
        assertEquals(ChatCopy(8, "Storgrim: Certainly"), result.chat)
    }
}
