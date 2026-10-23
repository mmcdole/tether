package com.mmcdole.tether.mud.ansi

import com.mmcdole.tether.mud.text.LineAssembler
import com.mmcdole.tether.mud.text.LineContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnsiParserTest {
    private fun parse(vararg chunks: String): Pair<List<LineContent>, LineContent> {
        val lines = ArrayList<LineContent>()
        val assembler = LineAssembler { lines += it }
        val parser = AnsiParser()
        chunks.forEach { parser.feed(it, assembler) }
        return lines to assembler.peekPartial()
    }

    @Test
    fun splitsLinesAndDropsCarriageReturns() {
        val (lines, partial) = parse("one\r\ntwo\r\nHP:10> ")
        assertEquals(listOf("one", "two"), lines.map { it.text })
        assertEquals("HP:10> ", partial.text)
    }

    @Test
    fun appliesBasicColors() {
        val (lines, _) = parse("\u001B[1;33mThe Village Square\u001B[0m\r\n")
        val line = lines.single()
        assertEquals("The Village Square", line.text)
        val style = line.spans.single().style
        assertEquals(AnsiColor.Indexed(3), style.foreground)
        assertEquals(AnsiColor.Indexed(11), style.effectiveForeground)
    }

    @Test
    fun mixedStylesOnOneLine() {
        val (lines, _) = parse("\u001B[32mHP:131\u001B[0m \u001B[94mSP:88\u001B[0m >\n")
        val line = lines.single()
        assertEquals("HP:131 SP:88 >", line.text)
        assertEquals(2, line.spans.size)
        assertEquals(0 until 6, line.spans[0].start until line.spans[0].end)
        assertEquals(AnsiColor.Indexed(12), line.spans[1].style.foreground)
    }

    @Test
    fun parses256AndTruecolor() {
        val (lines, _) = parse("\u001B[38;5;208mA\u001B[38;2;10;20;30mB\u001B[48;5;17mC\n")
        val spans = lines.single().spans
        assertEquals(AnsiColor.Indexed(208), spans[0].style.foreground)
        assertEquals(AnsiColor.Rgb(0x0A141E), spans[1].style.foreground)
        assertEquals(AnsiColor.Indexed(17), spans[2].style.background)
    }

    @Test
    fun escapeSplitAcrossReads() {
        val (lines, _) = parse("\u001B[3", "1mred\u001B[", "0m plain\n")
        val line = lines.single()
        assertEquals("red plain", line.text)
        assertEquals(AnsiColor.Indexed(1), line.spans.single().style.foreground)
        assertEquals(3, line.spans.single().end)
    }

    @Test
    fun stylePersistsAcrossLines() {
        val (lines, _) = parse("\u001B[36mA town guard.\nA small rat.\u001B[0m\n")
        assertEquals(AnsiColor.Indexed(6), lines[1].spans.single().style.foreground)
    }

    @Test
    fun stripsCursorAndTitleSequences() {
        val (lines, _) = parse("\u001B[2J\u001B[Hclear\u001B]0;title\u0007ed\n")
        assertEquals("cleared", lines.single().text)
        assertNull(lines.single().spans.firstOrNull())
    }

    @Test
    fun expandsTabs() {
        val (lines, _) = parse("ab\tc\n")
        assertEquals("ab      c", lines.single().text)
    }

    @Test
    fun resetsAttributesSelectively() {
        val (lines, _) = parse("\u001B[1;4;31mX\u001B[24mY\u001B[39mZ\n")
        val spans = lines.single().spans
        assertEquals(true, spans[0].style.underline)
        assertEquals(false, spans[1].style.underline)
        assertNull(spans[2].style.foreground)
        assertEquals(true, spans[2].style.bold)
    }

    @Test
    fun xtermCube() {
        assertEquals(0xFF8700, Xterm.rgb(208))
        assertEquals(0x080808, Xterm.rgb(232))
    }
}
