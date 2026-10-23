package com.mmcdole.tether.mud.command

import kotlin.test.Test
import kotlin.test.assertEquals

class CommandSplitterTest {
    @Test
    fun singleCommandIsUnchanged() {
        assertEquals(listOf("kill rat"), CommandSplitter.split("kill rat"))
    }

    @Test
    fun emptyInputIsOneEmptyCommand() {
        // A bare Enter still sends a newline, which MUDs use to page or redraw the prompt.
        assertEquals(listOf(""), CommandSplitter.split(""))
    }

    @Test
    fun splitsOnSemicolons() {
        assertEquals(listOf("n", "e", "open door"), CommandSplitter.split("n;e;open door"))
    }

    @Test
    fun keepsEmptyAndSpacedParts() {
        assertEquals(listOf("n", "", " e", ""), CommandSplitter.split("n;; e;"))
    }

    @Test
    fun escapedSemicolonIsLiteral() {
        assertEquals(listOf("say hi; there", "smile"), CommandSplitter.split("say hi\\; there;smile"))
    }

    @Test
    fun otherBackslashesAreKept() {
        assertEquals(listOf("say a\\b", "c\\"), CommandSplitter.split("say a\\b;c\\"))
    }

    @Test
    fun semicolonsInsideBracesAreNotSplit() {
        assertEquals(listOf("#alias f {stand;kill %1}", "n"), CommandSplitter.split("#alias f {stand;kill %1};n"))
    }

    @Test
    fun nestedBracesAndEscapesInsideBracesAreKept() {
        assertEquals(listOf("#action {a} {b;{c\\;d}}", "e"), CommandSplitter.split("#action {a} {b;{c\\;d}};e"))
    }

    @Test
    fun unmatchedCloseBraceIsLiteral() {
        assertEquals(listOf("say }", "n"), CommandSplitter.split("say };n"))
    }
}
