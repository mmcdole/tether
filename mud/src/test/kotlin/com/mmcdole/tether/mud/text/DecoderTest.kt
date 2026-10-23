package com.mmcdole.tether.mud.text

import kotlin.test.Test
import kotlin.test.assertEquals

class DecoderTest {
    @Test
    fun decodesUtf8() {
        assertEquals("Þórin – ok", Utf8OrLatin1Decoder().decode("Þórin – ok".toByteArray()))
    }

    @Test
    fun keepsSequenceSplitAcrossReads() {
        val bytes = "é".toByteArray()
        val d = Utf8OrLatin1Decoder()
        assertEquals("a", d.decode(byteArrayOf('a'.code.toByte(), bytes[0])))
        assertEquals("éb", d.decode(byteArrayOf(bytes[1], 'b'.code.toByte())))
    }

    // A Latin-1 byte at the very end of a read looks like the start of a UTF-8 sequence,
    // so it waits for the next read; here the line ending follows in the same read.
    @Test
    fun fallsBackToLatin1() {
        val latin1 = "café\r\n".toByteArray(Charsets.ISO_8859_1)
        assertEquals("café\r\n", Utf8OrLatin1Decoder().decode(latin1))
    }
}
