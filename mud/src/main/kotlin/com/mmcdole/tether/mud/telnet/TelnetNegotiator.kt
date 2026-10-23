package com.mmcdole.tether.mud.telnet

import java.io.ByteArrayOutputStream

/**
 * Decides how to answer the server's option requests and builds the bytes to send.
 * Supports ECHO, SGA, EOR, TTYPE (with MTTS), NAWS, CHARSET and MCCP2; refuses the rest.
 */
class TelnetNegotiator(
    private val clientName: String = "TETHER",
    private val compression: Boolean = true,
) {
    /** True while the server has asked to do the echoing, which means it is reading a password. */
    var serverEcho = false
        private set

    private var nawsEnabled = false
    private var columns = 80
    private var rows = 24
    private var ttypeIndex = 0
    // The last answer sent per option, for the server's side (DO/DONT) and ours (WILL/WONT).
    private val himReplies = HashMap<Int, Int>()
    private val usReplies = HashMap<Int, Int>()

    /** Returns the bytes to send back, or an empty array when no reply is needed. */
    fun onNegotiation(command: Int, option: Int): ByteArray = when (command) {
        Telnet.WILL -> when (option) {
            Telnet.OPT_ECHO -> { serverEcho = true; reply(Telnet.DO, option) }
            Telnet.OPT_SGA, Telnet.OPT_EOR -> reply(Telnet.DO, option)
            Telnet.OPT_MCCP2 -> if (compression) reply(Telnet.DO, option) else reply(Telnet.DONT, option)
            else -> reply(Telnet.DONT, option)
        }
        Telnet.WONT -> {
            if (option == Telnet.OPT_ECHO) serverEcho = false
            // Acknowledge only if we had agreed; the next WILL is then answered again.
            if (himReplies[option] == Telnet.DO) reply(Telnet.DONT, option) else ByteArray(0)
        }
        Telnet.DO -> when (option) {
            Telnet.OPT_TTYPE, Telnet.OPT_CHARSET -> reply(Telnet.WILL, option)
            Telnet.OPT_NAWS -> {
                nawsEnabled = true
                reply(Telnet.WILL, option) + nawsBytes()
            }
            else -> reply(Telnet.WONT, option)
        }
        Telnet.DONT -> {
            if (option == Telnet.OPT_NAWS) nawsEnabled = false
            if (usReplies[option] == Telnet.WILL) reply(Telnet.WONT, option) else ByteArray(0)
        }
        else -> ByteArray(0)
    }

    fun onSubnegotiation(option: Int, payload: ByteArray): ByteArray = when (option) {
        Telnet.OPT_TTYPE -> if (payload.firstOrNull()?.toInt() == TTYPE_SEND) ttypeReply() else ByteArray(0)
        Telnet.OPT_CHARSET -> charsetReply(payload)
        else -> ByteArray(0)
    }

    /** Records the window size and returns a NAWS update if the server asked for them. */
    fun setWindowSize(columns: Int, rows: Int): ByteArray {
        if (columns == this.columns && rows == this.rows) return ByteArray(0)
        this.columns = columns
        this.rows = rows
        return if (nawsEnabled) nawsBytes() else ByteArray(0)
    }

    /** Forgets everything negotiated, for a fresh connection. */
    fun reset() {
        serverEcho = false
        nawsEnabled = false
        ttypeIndex = 0
        himReplies.clear()
        usReplies.clear()
    }

    // Answers only when the state changes, so a server repeating itself doesn't start a loop.
    private fun reply(command: Int, option: Int): ByteArray {
        val replies = if (command == Telnet.DO || command == Telnet.DONT) himReplies else usReplies
        if (replies[option] == command) return ByteArray(0)
        replies[option] = command
        return byteArrayOf(Telnet.IAC.toByte(), command.toByte(), option.toByte())
    }

    private fun nawsBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(Telnet.IAC); out.write(Telnet.SB); out.write(Telnet.OPT_NAWS)
        for (value in intArrayOf(columns, rows)) {
            for (b in intArrayOf(value shr 8 and 0xFF, value and 0xFF)) {
                out.write(b)
                if (b == Telnet.IAC) out.write(b)
            }
        }
        out.write(Telnet.IAC); out.write(Telnet.SE)
        return out.toByteArray()
    }

    // MTTS: the client name, then the terminal type, then "MTTS <flags>", repeating the last.
    private fun ttypeReply(): ByteArray {
        val names = listOf(clientName, "XTERM-256COLOR", "MTTS $MTTS_FLAGS")
        val name = names[minOf(ttypeIndex, names.lastIndex)]
        ttypeIndex++
        return subnegotiation(Telnet.OPT_TTYPE, byteArrayOf(TTYPE_IS.toByte()) + name.toByteArray(Charsets.US_ASCII))
    }

    private fun charsetReply(payload: ByteArray): ByteArray {
        if (payload.isEmpty() || payload[0].toInt() != CHARSET_REQUEST) return ByteArray(0)
        if (payload.size < 2) return subnegotiation(Telnet.OPT_CHARSET, byteArrayOf(CHARSET_REJECTED.toByte()))
        val separator = payload[1].toInt().toChar()
        val offered = String(payload, 2, payload.size - 2, Charsets.US_ASCII).split(separator)
        val utf8 = offered.firstOrNull { it.equals("UTF-8", ignoreCase = true) || it.equals("UTF8", ignoreCase = true) }
        return if (utf8 != null) {
            subnegotiation(Telnet.OPT_CHARSET, byteArrayOf(CHARSET_ACCEPTED.toByte()) + utf8.toByteArray(Charsets.US_ASCII))
        } else {
            subnegotiation(Telnet.OPT_CHARSET, byteArrayOf(CHARSET_REJECTED.toByte()))
        }
    }

    private fun subnegotiation(option: Int, body: ByteArray): ByteArray =
        byteArrayOf(Telnet.IAC.toByte(), Telnet.SB.toByte(), option.toByte()) + body +
            byteArrayOf(Telnet.IAC.toByte(), Telnet.SE.toByte())

    private companion object {
        const val TTYPE_IS = 0
        const val TTYPE_SEND = 1
        const val CHARSET_REQUEST = 1
        const val CHARSET_ACCEPTED = 2
        const val CHARSET_REJECTED = 3
        // ANSI (1) + UTF-8 (4) + 256 colors (8) + truecolor (256).
        const val MTTS_FLAGS = 1 + 4 + 8 + 256
    }
}
