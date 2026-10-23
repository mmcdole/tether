package com.mmcdole.tether.mud.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PatternTest {
    private fun compile(source: String, regex: Boolean = false): Pattern =
        assertIs<PatternResult.Valid>(Pattern.compile(source, regex)).pattern

    private fun match(source: String, line: String, regex: Boolean = false) = compile(source, regex).match(line)

    @Test
    fun literalMatchesAnywhere() {
        assertEquals(listOf("hungry"), match("hungry", "You are hungry."))
        assertNull(match("thirsty", "You are hungry."))
    }

    @Test
    fun matchingIsCaseSensitive() {
        assertNull(match("you are hungry", "You are hungry."))
    }

    @Test
    fun wildcardInTheMiddleIsNonGreedy() {
        val captures = match("The %1 slashes you.", "The town guard slashes you.")
        assertEquals(listOf("The town guard slashes you.", "town guard"), captures)
    }

    @Test
    fun wildcardInTheMiddleStopsAtTheFirstMatch() {
        assertEquals("a", match("%1 and %2.", "a and b and c.")!![1])
    }

    @Test
    fun wildcardAtTheEndTakesTheRestOfTheLine() {
        val captures = match("%1 tells you: %2", "Hagbard tells you: wanna group for the troll? ok: now")
        assertEquals(listOf("Hagbard", "wanna group for the troll? ok: now"), captures!!.drop(1))
    }

    @Test
    fun leadingWildcardStartsAtTheLineStart() {
        assertEquals("Old Hagbard", match("%1 tells you", "Old Hagbard tells you hi")!![1])
    }

    @Test
    fun startAnchor() {
        assertEquals(listOf("HP:131", "131"), match("^HP:%1", "HP:131"))
        assertNull(match("^HP:%1", "Your HP:131"))
    }

    @Test
    fun endAnchorMakesTheLastWildcardNonGreedyUpToTheEnd() {
        assertEquals("131/160", match("HP:%1$", "HP:131/160")!![1])
        assertNull(match("hungry$", "You are hungry."))
        assertEquals(listOf("hungry."), match("hungry.$", "You are hungry."))
    }

    @Test
    fun bothAnchorsMustMatchTheWholeLine() {
        assertEquals("rat", match("^You kill %1.$", "You kill rat.")!![1])
        assertNull(match("^You kill %1.$", "You kill rat. Again!"))
        assertNull(match("^You kill %1.$", "Then You kill rat."))
    }

    @Test
    fun regexCharactersInPlainPatternsAreLiteral() {
        assertEquals(listOf("[ok] (1+1)*"), match("[ok] (1+1)*", "status [ok] (1+1)*"))
        assertNull(match("a.c", "abc"))
    }

    @Test
    fun anchorCharactersInTheMiddleAreLiteral() {
        assertEquals(listOf("cost $5 ^up"), match("cost $5 ^up", "cost $5 ^up"))
    }

    @Test
    fun doublePercentIsALiteralPercent() {
        assertEquals("50", match("%1%% done", "50% done")!![1])
    }

    @Test
    fun wildcardsCanBeOutOfOrder() {
        assertEquals(listOf("Bob gives sword", "sword", "Bob"), match("%2 gives %1", "Bob gives sword"))
    }

    @Test
    fun missingWildcardNumbersAreEmpty() {
        assertEquals(listOf("x says y", "", "", "y"), match("x says %3", "x says y"))
    }

    @Test
    fun repeatedWildcardMustMatchTheSameText() {
        assertEquals("Bob", match("%1 hits %1.", "Bob hits Bob.")!![1])
        assertNull(match("%1 hits %1.", "Bob hits Tom."))
    }

    @Test
    fun regexGroupsBecomeCaptures() {
        assertEquals(listOf("HP:131/160", "131", "160"), match("^HP:(\\d+)/(\\d+)", "HP:131/160", regex = true))
    }

    @Test
    fun unmatchedOptionalRegexGroupIsEmpty() {
        assertEquals(listOf("a", "a", ""), match("(a)|(b)", "a", regex = true))
    }

    @Test
    fun regexIsNotTreatedAsWildcards() {
        assertNull(match("%1 x", "foo x", regex = true))
    }

    @Test
    fun invalidRegexIsAnErrorResult() {
        val result = assertIs<PatternResult.Invalid>(Pattern.compile("HP:(\\d+", isRegex = true))
        assertTrue(result.message.startsWith("Invalid regex"), result.message)
    }

    @Test
    fun emptyPatternIsInvalid() {
        assertIs<PatternResult.Invalid>(Pattern.compile(""))
        assertIs<PatternResult.Invalid>(Pattern.compile("", isRegex = true))
    }

    @Test
    fun expandSubstitutesCaptures() {
        assertEquals("tell Hagbard hi", expand("tell %1 %2", listOf("x", "Hagbard", "hi")))
        assertEquals("all: x", expand("all: %0", listOf("x")))
    }

    @Test
    fun expandMissingCapturesAreEmptyAndPercentIsEscaped() {
        assertEquals("a  100%", expand("a %5 100%%", listOf("x")))
        assertEquals("50%x %", expand("50%x %", emptyList()))
    }

    @Test
    fun usesCapturesIgnoresEscapedPercent() {
        assertTrue(usesCaptures("kill %1"))
        assertTrue(usesCaptures("say %0"))
        assertEquals(false, usesCaptures("say 100%%1"))
        assertEquals(false, usesCaptures("kill"))
    }
}
