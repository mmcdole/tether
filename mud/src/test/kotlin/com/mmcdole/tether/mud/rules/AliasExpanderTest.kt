package com.mmcdole.tether.mud.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AliasExpanderTest {
    private fun aliases(vararg pairs: Pair<String, String>) =
        pairs.mapIndexed { i, (name, expansion) -> name to Alias(i.toLong(), Scope.Global, name, expansion) }.toMap()

    private fun expand(command: String, vararg pairs: Pair<String, String>): List<String> =
        assertIs<AliasResult.Commands>(AliasExpander.expand(command, aliases(*pairs))).commands

    @Test
    fun nonAliasPassesThroughUnchanged() {
        assertEquals(listOf("  look  "), expand("  look  ", "k" to "kill %1"))
        assertEquals(listOf(""), expand("", "k" to "kill %1"))
    }

    @Test
    fun matchesTheWholeFirstWordCaseSensitively() {
        assertEquals(listOf("kick rat"), expand("kick rat", "k" to "kill %1"))
        assertEquals(listOf("K rat"), expand("K rat", "k" to "kill %1"))
    }

    @Test
    fun numberedArguments() {
        assertEquals(listOf("kill guard"), expand("k guard", "k" to "kill %1"))
        assertEquals(listOf("give sword to bob"), expand("g bob sword", "g" to "give %2 to %1"))
    }

    @Test
    fun missingArgumentsAreEmpty() {
        assertEquals(listOf("kill "), expand("k", "k" to "kill %1"))
    }

    @Test
    fun percentZeroIsEverythingAfterTheName() {
        assertEquals(listOf("say hello  there"), expand("s   hello  there", "s" to "say %0"))
    }

    @Test
    fun argumentsAreAppendedWhenTheExpansionUsesNone() {
        assertEquals(listOf("kill big rat"), expand("k big rat", "k" to "kill"))
        assertEquals(listOf("kill"), expand("k", "k" to "kill"))
    }

    @Test
    fun appendedArgumentsGoOnTheLastCommand() {
        assertEquals(listOf("stand", "kill rat"), expand("sk rat", "sk" to "stand;kill"))
    }

    @Test
    fun semicolonsSplitAndEachPartIsExpandedAgain() {
        assertEquals(
            listOf("stand", "kill rat", "grin"),
            expand("f rat", "f" to "stand;k %1;grin", "k" to "kill %1"),
        )
    }

    @Test
    fun escapedSemicolonInExpansionIsLiteral() {
        assertEquals(listOf("say a;b"), expand("x", "x" to "say a\\;b"))
    }

    @Test
    fun argumentSemicolonIsNotSplit() {
        assertEquals(listOf("say a;b", "smile"), expand("s a;b", "s" to "say %0;smile"))
    }

    @Test
    fun tenLevelsAreAllowed() {
        val chain = (0 until 10).map { "a$it" to "a${it + 1}" }.toTypedArray()
        assertEquals(listOf("a10"), expand("a0", *chain))
    }

    @Test
    fun recursionPastTheLimitIsAnError() {
        val chain = (0 until 11).map { "a$it" to "a${it + 1}" }.toTypedArray()
        assertIs<AliasResult.Error>(AliasExpander.expand("a0", aliases(*chain)))
    }

    @Test
    fun selfReferenceIsAnError() {
        val result = assertIs<AliasResult.Error>(AliasExpander.expand("k rat", aliases("k" to "k %1")))
        assertTrue("\"k\"" in result.message, result.message)
    }
}
