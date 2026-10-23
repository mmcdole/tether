package com.mmcdole.tether.mud.telnet

import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Inflates an MCCP2 stream. [inflate] reports when the server ends compression. */
class Mccp2 {
    private val inflater = Inflater()
    private val buffer = ByteArray(16 * 1024)

    class Result(val output: ByteArray, val finished: Boolean, val remaining: ByteArray)

    /** @throws DataFormatException when the stream is corrupt. */
    fun inflate(input: ByteArray, offset: Int, length: Int): Result {
        inflater.setInput(input, offset, length)
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val n = inflater.inflate(buffer)
            if (n > 0) out.write(buffer, 0, n)
            if (inflater.finished()) {
                val left = inflater.remaining
                val remaining = input.copyOfRange(offset + length - left, offset + length)
                return Result(out.toByteArray(), finished = true, remaining = remaining)
            }
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
        }
        return Result(out.toByteArray(), finished = false, remaining = ByteArray(0))
    }

    fun end() = inflater.end()
}
