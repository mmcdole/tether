package com.mmcdole.tether.mud.telnet

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelnetNegotiatorTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun acceptsEchoAndTracksPasswordMode() {
        val n = TelnetNegotiator()
        assertContentEquals(bytes(255, 253, 1), n.onNegotiation(Telnet.WILL, Telnet.OPT_ECHO))
        assertTrue(n.serverEcho)
        n.onNegotiation(Telnet.WONT, Telnet.OPT_ECHO)
        assertEquals(false, n.serverEcho)
    }

    @Test
    fun answersEchoAgainAfterServerTurnsItOff() {
        val n = TelnetNegotiator()
        n.onNegotiation(Telnet.WILL, Telnet.OPT_ECHO)
        assertContentEquals(bytes(255, 254, 1), n.onNegotiation(Telnet.WONT, Telnet.OPT_ECHO))
        assertContentEquals(bytes(255, 253, 1), n.onNegotiation(Telnet.WILL, Telnet.OPT_ECHO))
        assertTrue(n.serverEcho)
        assertEquals(0, n.onNegotiation(Telnet.WILL, Telnet.OPT_ECHO).size)
    }

    @Test
    fun refusesUnknownOptionsOnce() {
        val n = TelnetNegotiator()
        assertContentEquals(bytes(255, 254, 201), n.onNegotiation(Telnet.WILL, 201))
        assertEquals(0, n.onNegotiation(Telnet.WILL, 201).size)
        assertContentEquals(bytes(255, 252, 99), n.onNegotiation(Telnet.DO, 99))
    }

    @Test
    fun sendsWindowSizeWhenAskedAndOnChange() {
        val n = TelnetNegotiator()
        n.setWindowSize(60, 30)
        val reply = n.onNegotiation(Telnet.DO, Telnet.OPT_NAWS)
        assertContentEquals(bytes(255, 251, 31, 255, 250, 31, 0, 60, 0, 30, 255, 240), reply)
        assertContentEquals(bytes(255, 250, 31, 0, 80, 0, 30, 255, 240), n.setWindowSize(80, 30))
        assertEquals(0, n.setWindowSize(80, 30).size)
    }

    @Test
    fun windowSizeOf255IsEscaped() {
        val n = TelnetNegotiator()
        n.onNegotiation(Telnet.DO, Telnet.OPT_NAWS)
        assertContentEquals(bytes(255, 250, 31, 0, 255, 255, 0, 24, 255, 240), n.setWindowSize(255, 24))
    }

    @Test
    fun cyclesTerminalTypesForMtts() {
        val n = TelnetNegotiator(clientName = "TETHER")
        val send = bytes(1)
        fun name(reply: ByteArray) = String(reply, 4, reply.size - 6, Charsets.US_ASCII)
        assertEquals("TETHER", name(n.onSubnegotiation(Telnet.OPT_TTYPE, send)))
        assertEquals("XTERM-256COLOR", name(n.onSubnegotiation(Telnet.OPT_TTYPE, send)))
        assertEquals("MTTS 269", name(n.onSubnegotiation(Telnet.OPT_TTYPE, send)))
        assertEquals("MTTS 269", name(n.onSubnegotiation(Telnet.OPT_TTYPE, send)))
    }

    @Test
    fun acceptsUtf8Charset() {
        val n = TelnetNegotiator()
        val request = bytes(1) + ";ISO-8859-1;UTF-8".toByteArray()
        val reply = n.onSubnegotiation(Telnet.OPT_CHARSET, request)
        assertContentEquals(bytes(255, 250, 42, 2) + "UTF-8".toByteArray() + bytes(255, 240), reply)
    }

    @Test
    fun rejectsCharsetWithoutUtf8() {
        val n = TelnetNegotiator()
        val reply = n.onSubnegotiation(Telnet.OPT_CHARSET, bytes(1) + " ASCII".toByteArray())
        assertContentEquals(bytes(255, 250, 42, 3, 255, 240), reply)
    }

    @Test
    fun compressionCanBeTurnedOff() {
        assertContentEquals(bytes(255, 253, 86), TelnetNegotiator().onNegotiation(Telnet.WILL, Telnet.OPT_MCCP2))
        assertContentEquals(bytes(255, 254, 86), TelnetNegotiator(compression = false).onNegotiation(Telnet.WILL, Telnet.OPT_MCCP2))
    }
}
