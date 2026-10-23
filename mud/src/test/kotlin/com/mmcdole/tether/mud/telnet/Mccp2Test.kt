package com.mmcdole.tether.mud.telnet

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Mccp2Test {
    private fun compress(text: String, finish: Boolean): ByteArray {
        val deflater = Deflater()
        deflater.setInput(text.toByteArray())
        if (finish) deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (true) {
            val n = deflater.deflate(buf, 0, buf.size, if (finish) Deflater.NO_FLUSH else Deflater.SYNC_FLUSH)
            if (n == 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    @Test
    fun inflatesStreamInPieces() {
        val compressed = compress("The Village Square\r\n", finish = false)
        val mccp = Mccp2()
        val half = compressed.size / 2
        val a = mccp.inflate(compressed, 0, half)
        val b = mccp.inflate(compressed, half, compressed.size - half)
        assertEquals("The Village Square\r\n", String(a.output + b.output))
    }

    @Test
    fun reportsEndAndReturnsTrailingBytes() {
        val compressed = compress("bye", finish = true) + "plain".toByteArray()
        val result = Mccp2().inflate(compressed, 0, compressed.size)
        assertTrue(result.finished)
        assertEquals("bye", String(result.output))
        assertEquals("plain", String(result.remaining))
    }
}
