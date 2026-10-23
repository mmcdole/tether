package com.mmcdole.tether.mud.telnet

import java.io.ByteArrayOutputStream

sealed interface TelnetEvent {
    /** Plain bytes for the terminal, with IAC sequences removed. */
    class Data(val bytes: ByteArray) : TelnetEvent

    /** WILL, WONT, DO or DONT for an option. */
    data class Negotiation(val command: Int, val option: Int) : TelnetEvent

    class Subnegotiation(val option: Int, val payload: ByteArray) : TelnetEvent

    /** IAC GA or IAC EOR: the text before it is a prompt. */
    data object PromptEnd : TelnetEvent

    /**
     * The server switched on MCCP2. Every byte after this one is compressed, so the
     * parser stops and reports how many input bytes it consumed.
     */
    data object CompressionStart : TelnetEvent
}

/**
 * Splits a telnet byte stream into data and commands. Keeps state between calls, so
 * a sequence split across two socket reads is handled.
 */
class TelnetParser {
    private enum class State { DATA, IAC, NEGOTIATE, SB, SB_DATA, SB_IAC }

    private var state = State.DATA
    private var negotiateCommand = 0
    private var sbOption = 0
    private val sbBuffer = ByteArrayOutputStream()

    class Result(val events: List<TelnetEvent>, val consumed: Int)

    fun parse(input: ByteArray, offset: Int = 0, length: Int = input.size - offset): Result {
        val events = ArrayList<TelnetEvent>()
        val data = ByteArrayOutputStream()
        fun flushData() {
            if (data.size() > 0) {
                events += TelnetEvent.Data(data.toByteArray())
                data.reset()
            }
        }

        var i = offset
        val end = offset + length
        while (i < end) {
            val b = input[i].toInt() and 0xFF
            i++
            when (state) {
                State.DATA -> if (b == Telnet.IAC) state = State.IAC else data.write(b)
                State.IAC -> when (b) {
                    Telnet.IAC -> { data.write(b); state = State.DATA }
                    Telnet.WILL, Telnet.WONT, Telnet.DO, Telnet.DONT -> {
                        negotiateCommand = b
                        state = State.NEGOTIATE
                    }
                    Telnet.SB -> state = State.SB
                    Telnet.GA, Telnet.EOR -> {
                        flushData()
                        events += TelnetEvent.PromptEnd
                        state = State.DATA
                    }
                    else -> state = State.DATA // NOP and the rest carry nothing for us
                }
                State.NEGOTIATE -> {
                    flushData()
                    events += TelnetEvent.Negotiation(negotiateCommand, b)
                    state = State.DATA
                }
                State.SB -> {
                    sbOption = b
                    sbBuffer.reset()
                    state = State.SB_DATA
                }
                State.SB_DATA -> if (b == Telnet.IAC) state = State.SB_IAC else sbBuffer.write(b)
                State.SB_IAC -> when (b) {
                    Telnet.SE -> {
                        flushData()
                        state = State.DATA
                        if (sbOption == Telnet.OPT_MCCP2) {
                            events += TelnetEvent.CompressionStart
                            return Result(events, i - offset)
                        }
                        events += TelnetEvent.Subnegotiation(sbOption, sbBuffer.toByteArray())
                    }
                    Telnet.IAC -> { sbBuffer.write(b); state = State.SB_DATA }
                    else -> state = State.SB_DATA
                }
            }
        }
        flushData()
        return Result(events, length)
    }
}
