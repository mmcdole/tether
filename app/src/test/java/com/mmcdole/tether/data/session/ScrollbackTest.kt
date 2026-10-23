package com.mmcdole.tether.data.session

import com.mmcdole.tether.mud.ansi.AnsiColor
import com.mmcdole.tether.mud.ansi.TextStyle
import com.mmcdole.tether.mud.text.LineContent
import com.mmcdole.tether.mud.text.LineKind
import com.mmcdole.tether.mud.text.StyleSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ScrollbackTest {
    private fun text(s: String) = LineContent(s, emptyList())

    @Test
    fun dropsOldestPastCapacityWithIdsStillIncreasing() {
        val scrollback = Scrollback(capacity = 3)
        repeat(5) { scrollback.add(text("line $it")) }
        val lines = scrollback.snapshot().lines
        assertEquals(listOf("line 2", "line 3", "line 4"), lines.map { it.text })
        assertEquals(listOf(3L, 4L, 5L), lines.map { it.id })
    }

    @Test
    fun partialBorrowsNextIdAndKeepsItOnceFinished() {
        val scrollback = Scrollback()
        scrollback.add(text("hello"))
        val red = listOf(StyleSpan(0, 2, TextStyle(foreground = AnsiColor.Indexed(1))))
        val partial = scrollback.snapshot(LineContent("HP>", red)).lines.last()
        assertEquals(2L, partial.id)
        assertEquals(red, partial.spans)

        val finished = scrollback.add(LineContent("HP>", red), LineKind.Prompt)
        assertEquals(partial.id, finished.id)
        assertEquals(2, scrollback.snapshot().lines.size)
    }

    @Test
    fun emptyPartialIsNotShown() {
        val scrollback = Scrollback()
        scrollback.add(text("a"))
        assertEquals(1, scrollback.snapshot(LineContent.Empty).lines.size)
    }

    @Test
    fun replaceLastKeepsIdAndUnchangedLinesKeepIdentity() {
        val scrollback = Scrollback()
        val first = scrollback.add(text("a"))
        val prompt = scrollback.add(text(">"), LineKind.Prompt)
        scrollback.replaceLast(prompt.copy(text = "> look", echoStart = 2))

        val lines = scrollback.snapshot().lines
        assertSame(first, lines[0])
        assertEquals(prompt.id, lines[1].id)
        assertEquals("> look", lines[1].text)
    }

    @Test
    fun idsKeepIncreasingAfterClear() {
        val scrollback = Scrollback()
        scrollback.add(text("a"))
        scrollback.clear()
        assertEquals(2L, scrollback.add(text("b")).id)
    }

    @Test
    fun loweringCapacityDropsOldestAndRaisingItKeepsMore() {
        val scrollback = Scrollback(capacity = 5)
        repeat(5) { scrollback.add(text("line $it")) }
        scrollback.capacity = 2
        assertEquals(listOf("line 3", "line 4"), scrollback.snapshot().lines.map { it.text })

        scrollback.capacity = 4
        repeat(3) { scrollback.add(text("more $it")) }
        assertEquals(listOf("line 4", "more 0", "more 1", "more 2"), scrollback.snapshot().lines.map { it.text })
    }
}
