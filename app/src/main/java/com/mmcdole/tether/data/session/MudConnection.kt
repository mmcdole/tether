package com.mmcdole.tether.data.session

import com.mmcdole.tether.mud.ansi.AnsiParser
import com.mmcdole.tether.mud.telnet.Mccp2
import com.mmcdole.tether.mud.telnet.Telnet
import com.mmcdole.tether.mud.telnet.TelnetEvent
import com.mmcdole.tether.mud.telnet.TelnetNegotiator
import com.mmcdole.tether.mud.telnet.TelnetParser
import com.mmcdole.tether.mud.text.LineAssembler
import com.mmcdole.tether.mud.text.LineContent
import com.mmcdole.tether.mud.text.Utf8OrLatin1Decoder
import java.io.IOException
import java.util.zip.DataFormatException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What one [MudConnection.read] produced. */
sealed interface ReadResult

/** Finished lines in order, and the unfinished text after them. */
class Received(val lines: List<ReceivedLine>, val partial: LineContent) : ReadResult

/** How a connection ended. */
sealed interface ConnectionEnd : ReadResult {
    /** The server closed the stream cleanly: quit, kicked, idled out, or rebooting. */
    data object Closed : ConnectionEnd

    /** The connection broke: reset, timeout, broken pipe, or its network went away. */
    class Failed(val error: IOException) : ConnectionEnd
}

/** The connection's network went away, so [MudConnection.abort] closed it. */
class NetworkLostException : IOException("Network lost")

data class ReceivedLine(val content: LineContent, val prompt: Boolean)

/**
 * One connection: the socket, its write channel, and the protocol pipeline
 * (MCCP2 → telnet → decode → ANSI → lines). Blocking IO runs on [io]. Everything else
 * must be called from one coroutine context at a time, which SessionManager guarantees.
 */
class MudConnection private constructor(private val socket: MudSocket, private val io: CoroutineDispatcher) {
    private val outgoing = Channel<ByteArray>(Channel.UNLIMITED)
    private val buffer = ByteArray(16 * 1024)

    private val telnet = TelnetParser()
    private val negotiator = TelnetNegotiator()
    private var mccp: Mccp2? = null
    private val decoder = Utf8OrLatin1Decoder()
    private val ansi = AnsiParser()
    private val lines = ArrayList<ReceivedLine>()
    private val assembler = LineAssembler(::onLine)

    // When bytes last went either way, from System.nanoTime().
    @Volatile private var lastTraffic = System.nanoTime()

    // A GA/EOR prompt already ends its line, so a newline right after it would leave a blank line.
    private var afterPrompt = false

    @Volatile private var aborted = false

    /** True while the server echoes for us, which means it's asking for a password. */
    val serverEcho: Boolean get() = negotiator.serverEcho

    /** Writes queued bytes until the connection closes. */
    suspend fun runWriter() = withContext(io) {
        for (bytes in outgoing) {
            socket.output.write(bytes)
            socket.output.flush()
            lastTraffic = System.nanoTime()
        }
    }

    /** Sends IAC NOP after [idle] with no traffic either way, so NAT and routers keep the socket open. */
    suspend fun runKeepalive(idle: Duration = KEEPALIVE_IDLE) {
        while (true) {
            val quiet = (System.nanoTime() - lastTraffic).nanoseconds
            if (quiet >= idle) {
                write(Telnet.NOP_BYTES)
                delay(idle)
            } else {
                delay(idle - quiet)
            }
        }
    }

    /** Waits for one read and parses it, or says how the connection ended. */
    suspend fun read(): ReadResult {
        val n = try {
            withContext(io) { socket.input.read(buffer) }
        } catch (e: IOException) {
            return ConnectionEnd.Failed(if (aborted) NetworkLostException() else e)
        }
        // A socket closed under the read can look like a clean end of stream.
        if (aborted) return ConnectionEnd.Failed(NetworkLostException())
        if (n < 0) return ConnectionEnd.Closed
        lastTraffic = System.nanoTime()
        try {
            feed(buffer, 0, n)
        } catch (_: DataFormatException) {
            return ConnectionEnd.Failed(IOException("Compression error"))
        }
        val result = Received(lines.toList(), assembler.peekPartial())
        lines.clear()
        return result
    }

    fun sendLine(text: String) {
        // Our Enter ends the prompt line, unless the server echoes, in which case its newline does.
        if (!serverEcho) afterPrompt = false
        // UTF-8 never contains 0xFF, so there is no IAC to escape.
        write((text + "\r\n").toByteArray(Charsets.UTF_8))
    }

    /** Removes the unfinished line, so a command can be shown after it. */
    fun takePartial(): LineContent? = if (assembler.hasPartial) assembler.take() else null

    fun setWindowSize(columns: Int, rows: Int) = write(negotiator.setWindowSize(columns, rows))

    /** Closes the socket under a blocked read, which then fails with [NetworkLostException]. The caller still calls [close]. */
    fun abort() {
        aborted = true
        runCatching { socket.close() }
    }

    fun close() {
        outgoing.close()
        mccp?.end()
        mccp = null
        runCatching { socket.close() }
    }

    private fun write(bytes: ByteArray) {
        if (bytes.isNotEmpty()) outgoing.trySend(bytes)
    }

    private fun feed(input: ByteArray, offset: Int, length: Int) {
        val inflater = mccp ?: return feedTelnet(input, offset, length)
        val result = inflater.inflate(input, offset, length)
        feedTelnet(result.output, 0, result.output.size)
        if (result.finished) {
            inflater.end()
            mccp = null
            feedTelnet(result.remaining, 0, result.remaining.size)
        }
    }

    private fun feedTelnet(input: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        val result = telnet.parse(input, offset, length)
        for (event in result.events) {
            when (event) {
                is TelnetEvent.Data -> ansi.feed(decoder.decode(event.bytes), assembler)
                is TelnetEvent.Negotiation -> write(negotiator.onNegotiation(event.command, event.option))
                is TelnetEvent.Subnegotiation -> write(negotiator.onSubnegotiation(event.option, event.payload))
                TelnetEvent.PromptEnd -> if (assembler.hasPartial) {
                    lines += ReceivedLine(assembler.take(), prompt = true)
                    afterPrompt = true
                }
                TelnetEvent.CompressionStart -> mccp = Mccp2()
            }
        }
        // The parser stops at the start of compression; whatever follows in this read is compressed.
        if (result.consumed < length) feed(input, offset + result.consumed, length - result.consumed)
    }

    private fun onLine(content: LineContent) {
        val skip = afterPrompt && content.text.isEmpty()
        afterPrompt = false
        if (!skip) lines += ReceivedLine(content, prompt = false)
    }

    companion object {
        val KEEPALIVE_IDLE = 60.seconds

        suspend fun open(factory: MudSocketFactory, host: String, port: Int, tls: Boolean, io: CoroutineDispatcher): MudConnection {
            // Keep hold of the socket so a cancel that lands as it opens still closes it.
            var socket: MudSocket? = null
            try {
                withContext(io) { socket = factory.open(host, port, tls) }
            } catch (e: CancellationException) {
                socket?.close()
                throw e
            }
            return MudConnection(socket!!, io)
        }
    }
}
