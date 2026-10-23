package com.mmcdole.tether.mud.telnet

/** Telnet command bytes and option codes, as unsigned ints. */
object Telnet {
    const val IAC = 255
    const val DONT = 254
    const val DO = 253
    const val WONT = 252
    const val WILL = 251
    const val SB = 250
    const val GA = 249
    const val NOP = 241
    const val SE = 240
    const val EOR = 239

    const val OPT_ECHO = 1
    const val OPT_SGA = 3
    const val OPT_TTYPE = 24
    const val OPT_EOR = 25
    const val OPT_NAWS = 31
    const val OPT_CHARSET = 42
    const val OPT_MCCP2 = 86

    val NOP_BYTES = byteArrayOf(IAC.toByte(), NOP.toByte())
}
