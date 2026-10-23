package com.mmcdole.tether.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyTest {
    @Test
    fun prefixThenText() = assertEquals("tell Hagbard sure, omw", replyCommand("tell Hagbard", "sure, omw"))

    @Test
    fun surroundingSpaceIsTrimmed() = assertEquals("tell Hagbard sure", replyCommand("tell Hagbard ", "  sure \n"))

    @Test
    fun lineBreaksBecomeSpaces() = assertEquals("tell Hagbard one two", replyCommand("tell Hagbard", "one\n two"))

    @Test
    fun semicolonsAndHashesAreLeftAlone() =
        assertEquals("tell Hagbard a;b #c", replyCommand("tell Hagbard", "a;b #c"))

    @Test
    fun blankTextSendsNothing() {
        assertNull(replyCommand("tell Hagbard", ""))
        assertNull(replyCommand("tell Hagbard", "  \n "))
    }

    @Test
    fun emptyPrefixSendsTheTextAlone() = assertEquals("sure", replyCommand("", "sure"))
}
