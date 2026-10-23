package com.mmcdole.tether.mud.text

/**
 * Decodes UTF-8, keeping a sequence cut off at the end of one read for the next. Bytes
 * that are not valid UTF-8 are read as Latin-1, so older MUDs that send accented
 * Latin-1 still look right.
 */
class Utf8OrLatin1Decoder {
    private var pending = ByteArray(0)

    fun decode(bytes: ByteArray): String {
        val input = if (pending.isEmpty()) bytes else pending + bytes
        pending = ByteArray(0)
        val out = StringBuilder(input.size)
        var i = 0
        while (i < input.size) {
            val b = input[i].toInt() and 0xFF
            val need = when {
                b < 0x80 -> 0
                b in 0xC2..0xDF -> 1
                b in 0xE0..0xEF -> 2
                b in 0xF0..0xF4 -> 3
                else -> -1
            }
            if (need <= 0) {
                out.append(b.toChar())
                i++
                continue
            }
            val available = input.size - i - 1
            if (available < need && (1..available).all { isContinuation(input[i + it]) }) {
                pending = input.copyOfRange(i, input.size)
                break
            }
            if (available >= need && (1..need).all { isContinuation(input[i + it]) }) {
                var cp = b and (0xFF shr (need + 2))
                for (k in 1..need) cp = (cp shl 6) or (input[i + k].toInt() and 0x3F)
                out.appendCodePoint(cp)
                i += need + 1
            } else {
                out.append(b.toChar())
                i++
            }
        }
        return out.toString()
    }

    fun reset() {
        pending = ByteArray(0)
    }

    private fun isContinuation(b: Byte) = (b.toInt() and 0xC0) == 0x80
}
