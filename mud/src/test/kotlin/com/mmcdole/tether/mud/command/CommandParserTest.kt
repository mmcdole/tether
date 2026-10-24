package com.mmcdole.tether.mud.command

import com.mmcdole.tether.mud.rules.Alias
import com.mmcdole.tether.mud.rules.Scope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CommandParserTest {
    private fun parse(input: String, vararg aliases: Pair<String, String>) = CommandParser.parse(
        input,
        aliases.associate { (name, expansion) -> name to Alias(0, Scope.Global, name, expansion) },
    )

    private fun one(input: String) = parse(input).single()

    @Test
    fun plainInputIsSent() {
        assertEquals(listOf(Step.Send("kill rat")), parse("kill rat"))
        assertEquals(listOf(Step.Send("")), parse(""))
    }

    @Test
    fun splitsOnSemicolonsAndKeepsEscapedOnes() {
        assertEquals(listOf(Step.Send("n"), Step.Send("say a;b"), Step.Send("e")), parse("n;say a\\;b;e"))
    }

    @Test
    fun aliasesAreExpanded() {
        assertEquals(listOf(Step.Send("kill rat"), Step.Send("n")), parse("k rat;n", "k" to "kill %1"))
    }

    @Test
    fun aliasRecursionErrorBecomesAnErrorStep() {
        val steps = parse("k;n", "k" to "k")
        assertIs<Step.Error>(steps[0])
        assertEquals(Step.Send("n"), steps[1])
    }

    @Test
    fun aliasWithBracedArguments() {
        assertEquals(Step.AddAlias("k", "kill %1", global = false), one("#alias {k} {kill %1}"))
    }

    @Test
    fun galiasIsGlobal() {
        assertEquals(Step.AddAlias("k", "kill %1", global = true), one("#galias k {kill %1}"))
    }

    @Test
    fun unbracedLastArgumentTakesTheRestOfTheLine() {
        assertEquals(Step.AddAlias("k", "kill %1 now", global = false), one("#alias k kill %1 now  "))
    }

    @Test
    fun semicolonInsideBracesIsNotSplit() {
        assertEquals(listOf(Step.AddAlias("f", "stand;kill %1", global = false), Step.Send("n")), parse("#alias f {stand;kill %1};n"))
    }

    @Test
    fun escapedSemicolonInsideBracesIsKeptForLater() {
        assertEquals(Step.AddAlias("s", "say a\\;b", global = false), one("#alias s {say a\\;b}"))
    }

    @Test
    fun escapedSemicolonInUnbracedArgumentBecomesPartOfTheExpansion() {
        assertEquals(Step.AddAlias("f", "stand;kill", global = false), one("#alias f stand\\;kill"))
    }

    @Test
    fun nestedBraces() {
        assertEquals(
            Step.AddAction("%1 arrives.", "#gag {%1 leaves.}", global = false),
            one("#action {%1 arrives.} {#gag {%1 leaves.}}"),
        )
    }

    @Test
    fun missingCloseBraceIsAnError() {
        assertIs<Step.Error>(one("#alias {k {kill"))
    }

    @Test
    fun textAfterTheLastBracedArgumentIsAnError() {
        assertIs<Step.Error>(one("#gag {a} {b}"))
    }

    @Test
    fun aliasNameWithSpacesIsAnError() {
        assertIs<Step.Error>(one("#alias {k r} {kill rat}"))
    }

    @Test
    fun aliasWithOneArgumentIsUsage() {
        assertEquals(Step.Error("Usage: #alias {name} {expansion}"), one("#alias k"))
    }

    @Test
    fun aliasAloneLists() {
        assertEquals(Step.ListAliases, one("#alias"))
        assertEquals(Step.ListAliases, one("#galias  "))
    }

    @Test
    fun actionAndGaction() {
        assertEquals(Step.AddAction("You are hungry.", "eat bread", global = false), one("#action {You are hungry.} {eat bread}"))
        assertEquals(Step.AddAction("%1 tells you: %2", "say hi", global = true), one("#gaction {%1 tells you: %2} say hi"))
        assertEquals(Step.ListTriggers, one("#action"))
    }

    @Test
    fun gagAndGgag() {
        assertEquals(Step.AddGag("The fountain gurgles.", global = false), one("#gag The fountain gurgles."))
        assertEquals(Step.AddGag("spam", global = true), one("#ggag {spam}"))
        assertEquals(Step.Error("Usage: #gag {pattern}"), one("#gag"))
    }

    @Test
    fun removals() {
        assertEquals(Step.RemoveAlias("k"), one("#unalias k"))
        assertEquals(Step.RemoveAction("You are hungry."), one("#unaction {You are hungry.}"))
        assertEquals(Step.RemoveGag("The fountain gurgles."), one("#ungag The fountain gurgles."))
        assertIs<Step.Error>(one("#unalias"))
    }

    @Test
    fun commandNamesAreCaseInsensitive() {
        assertEquals(Step.RemoveAlias("k"), one("#UNALIAS k"))
    }

    @Test
    fun unknownCommand() {
        assertEquals(Step.Error("Unknown command: #foo"), one("#foo bar"))
        assertEquals(Step.Error("Unknown command: #"), one("#"))
    }

    @Test
    fun leadingSpaceBeforeHashIsStillLocal() {
        assertEquals(listOf(Step.Send("n"), Step.RemoveAlias("k")), parse("n; #unalias k"))
    }

    @Test
    fun localCommandsAreNotAliasExpanded() {
        assertEquals(Step.RemoveAlias("k"), parse("#unalias k", "#unalias" to "x").single())
    }

    @Test
    fun aliasCanExpandToALocalCommand() {
        assertEquals(listOf(Step.AddGag("spam", global = false)), parse("nospam", "nospam" to "#gag {spam}"))
    }

    @Test
    fun parseCommandDoesNotSplit() {
        assertEquals(listOf(Step.Send("say hi;drop all")), CommandParser.parseCommand("say hi;drop all"))
    }

    @Test
    fun speedwalksExpandAfterAliases() {
        assertEquals(List(3) { Step.Send("e") } + Step.Send("n"), parse("3en"))
        assertEquals(List(4) { Step.Send("w") }, parse("home", "home" to "4w"))
        assertEquals(listOf(Step.Send("news")), parse("news"))
    }

    @Test
    fun tickerTakesNameCommandAndSeconds() {
        assertEquals(Step.AddTicker("heal", "cast heal;grin", 1000), one("#ticker heal {cast heal;grin} 1"))
        assertEquals(Step.AddTicker("tk", "save", 2500), one("#ticker {tk} {save} {2.5}"))
        assertEquals(Step.ListTickers, one("#ticker"))
        assertEquals(Step.RemoveTicker("heal"), one("#unticker heal"))
    }

    @Test
    fun tickerNeedsAtLeastHalfASecond() {
        assertIs<Step.Error>(one("#ticker heal {cast heal} 0.1"))
        assertIs<Step.Error>(one("#ticker heal {cast heal} soon"))
        assertIs<Step.Error>(one("#ticker heal {cast heal}"))
    }
}
