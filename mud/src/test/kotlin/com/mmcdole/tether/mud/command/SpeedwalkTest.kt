package com.mmcdole.tether.mud.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpeedwalkTest {
    private fun walk(command: String) = Speedwalk.expand(command)?.joinToString("")

    @Test
    fun countRepeatsTheDirection() {
        assertEquals("eeeeeeeeee", walk("10e"))
        assertEquals("nnneew", walk("3n2ew"))
    }

    @Test
    fun repeatedLettersWalk() {
        assertEquals("eeeeeeeeee", walk("eeeeeeeeee"))
        assertEquals("eeennnuu", walk("eeennnuu"))
    }

    @Test
    fun wordsMadeOfDirectionLettersAreNotWalks() {
        for (word in listOf("news", "use", "sun", "send", "need", "seen", "nee", "e", "n", "ne")) {
            assertNull(walk(word), word)
        }
    }

    @Test
    fun anythingElseIsNotAWalk() {
        for (command in listOf("", "10", "10x", "e10", "10 e", "kill 2rat", "0e", "100e")) {
            assertNull(walk(command), command)
        }
    }
}
