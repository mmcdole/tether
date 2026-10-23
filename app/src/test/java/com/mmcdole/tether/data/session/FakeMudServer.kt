package com.mmcdole.tether.data.session

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * In-memory MUD: each [send] arrives as one socket read on the newest connection; lines
 * the client writes on any connection land in [awaitLine].
 */
class FakeMudServer {
    @Volatile private var pipe = Pipe()
    private val fromClient = LinkedBlockingQueue<String>()
    private val written = ByteArrayOutputStream()
    @Volatile var opened = 0
        private set

    /** Connection attempts, including refused ones. */
    @Volatile var attempts = 0
        private set

    /** While true, connecting fails as if the server were down. */
    @Volatile var down = false

    /** A factory that refuses, like a server that isn't running. */
    val refusing = MudSocketFactory { _, _, _ -> throw java.net.ConnectException("ECONNREFUSED (Connection refused)") }

    val factory = MudSocketFactory { host, port, tls ->
        attempts++
        if (down) refusing.open(host, port, tls)
        if (opened++ > 0) pipe = Pipe()
        val current = pipe
        MudSocket(current.input, output) { current.queue.put(EOF) }
    }

    fun send(text: String) = pipe.queue.put(text.toByteArray(Charsets.UTF_8))

    fun send(bytes: ByteArray) = pipe.queue.put(bytes)

    /** Closes the newest connection cleanly from the server's end. */
    fun close() = pipe.queue.put(EOF)

    /** Breaks the newest connection, as a reset or timeout would. */
    fun reset() = pipe.queue.put(RESET)

    fun awaitLine(): String = fromClient.poll(2, TimeUnit.SECONDS) ?: throw AssertionError("nothing sent")

    fun noMoreLines(): Boolean = fromClient.poll(200, TimeUnit.MILLISECONDS) == null

    /** Everything the client wrote, including telnet replies. */
    fun writtenBytes(): ByteArray = synchronized(written) { written.toByteArray() }

    private class Pipe {
        val queue = LinkedBlockingQueue<ByteArray>()

        val input = object : InputStream() {
            private var current = ByteArray(0)
            private var pos = 0

            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= current.size) {
                    current = queue.take()
                    pos = 0
                    if (current === EOF) {
                        queue.put(EOF)
                        return -1
                    }
                    if (current === RESET) {
                        queue.put(RESET)
                        throw java.net.SocketException("Connection reset")
                    }
                }
                val n = minOf(len, current.size - pos)
                System.arraycopy(current, pos, b, off, n)
                pos += n
                return n
            }
        }
    }

    // Collects text lines, skipping telnet replies (IAC cmd [opt], or IAC SB … IAC SE).
    private val output = object : OutputStream() {
        private val line = ByteArrayOutputStream()
        private var skip = 0
        private var inSb = false
        private var last = 0

        override fun write(b: Int) {
            val v = b and 0xFF
            synchronized(written) { written.write(v) }
            val prev = last
            last = v
            when {
                inSb -> if (prev == 0xFF && v == 0xF0) inSb = false
                skip > 0 -> {
                    skip--
                    if (v == 0xFA) { inSb = true; skip = 0 } else if (v in 0xFB..0xFE) skip = 1
                }
                v == 0xFF -> skip = 1
                v == '\n'.code -> {
                    fromClient.put(line.toString(Charsets.UTF_8).removeSuffix("\r"))
                    line.reset()
                }
                else -> line.write(v)
            }
        }
    }

    private companion object {
        val EOF = ByteArray(0)
        val RESET = ByteArray(0)
    }
}
