package com.mmcdole.tether.mud.telnet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TelnetParserTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun dataOf(events: List<TelnetEvent>) =
        events.filterIsInstance<TelnetEvent.Data>().joinToString("") { String(it.bytes, Charsets.ISO_8859_1) }

    @Test
    fun passesPlainTextThrough() {
        val result = TelnetParser().parse("Hello\r\n".toByteArray())
        assertEquals("Hello\r\n", dataOf(result.events))
    }

    @Test
    fun reportsNegotiationsAndStripsThem() {
        val input = "ab".toByteArray() + bytes(255, 251, 1) + "cd".toByteArray()
        val events = TelnetParser().parse(input).events
        assertEquals("abcd", dataOf(events))
        assertEquals(TelnetEvent.Negotiation(Telnet.WILL, Telnet.OPT_ECHO), events[1])
    }

    @Test
    fun escapedIacIsData() {
        val events = TelnetParser().parse(bytes(65, 255, 255, 66)).events
        assertEquals("AÿB", dataOf(events))
    }

    @Test
    fun handlesSequenceSplitAcrossReads() {
        val parser = TelnetParser()
        val first = parser.parse(bytes(65, 255)).events
        val second = parser.parse(bytes(253, 31, 66)).events
        assertEquals("A", dataOf(first))
        assertEquals(TelnetEvent.Negotiation(Telnet.DO, Telnet.OPT_NAWS), second.first())
        assertEquals("B", dataOf(second))
    }

    @Test
    fun parsesSubnegotiation() {
        val events = TelnetParser().parse(bytes(255, 250, 24, 1, 255, 240)).events
        val sb = assertIs<TelnetEvent.Subnegotiation>(events.single())
        assertEquals(Telnet.OPT_TTYPE, sb.option)
        assertEquals(listOf<Byte>(1), sb.payload.toList())
    }

    @Test
    fun goAheadMarksPrompt() {
        val events = TelnetParser().parse("HP:10> ".toByteArray() + bytes(255, 249)).events
        assertEquals("HP:10> ", dataOf(events))
        assertEquals(TelnetEvent.PromptEnd, events.last())
    }

    @Test
    fun stopsAtCompressionStart() {
        val input = "hi".toByteArray() + bytes(255, 250, 86, 255, 240) + bytes(1, 2, 3)
        val result = TelnetParser().parse(input)
        assertEquals(TelnetEvent.CompressionStart, result.events.last())
        assertEquals(input.size - 3, result.consumed)
    }
}
