package com.mmcdole.tether.mud.ansi

/** Receives styled text and line breaks from [AnsiParser]. */
interface AnsiSink {
    fun text(text: String, style: TextStyle)
    fun newline()
}

/**
 * Turns text with ANSI escape codes into styled runs. SGR codes set the style; other
 * escape sequences (cursor movement, OSC titles) are removed. Keeps state between
 * calls, so a sequence split across reads works.
 */
class AnsiParser {
    private enum class State { TEXT, ESC, CSI, OSC, OSC_ESC }

    var style = TextStyle.Default
        private set

    private var state = State.TEXT
    private val params = StringBuilder()
    private val run = StringBuilder()
    private var column = 0

    fun feed(input: String, sink: AnsiSink) {
        for (c in input) {
            when (state) {
                State.TEXT -> when (c) {
                    ESC -> { flush(sink); state = State.ESC }
                    '\n' -> { flush(sink); sink.newline(); column = 0 }
                    '\t' -> {
                        val spaces = 8 - column % 8
                        repeat(spaces) { run.append(' ') }
                        column += spaces
                    }
                    '\r', '\u0007', '\u0000' -> Unit
                    else -> if (c >= ' ') { run.append(c); column++ }
                }
                State.ESC -> state = when (c) {
                    '[' -> { params.setLength(0); State.CSI }
                    ']' -> State.OSC
                    else -> State.TEXT
                }
                State.CSI -> when {
                    c in '0'..'9' || c == ';' || c == ':' || c == '?' -> params.append(c)
                    c in '@'..'~' -> {
                        if (c == 'm') applySgr(params.toString())
                        state = State.TEXT
                    }
                    else -> state = State.TEXT
                }
                State.OSC -> when (c) {
                    '\u0007' -> state = State.TEXT
                    ESC -> state = State.OSC_ESC
                    else -> Unit
                }
                State.OSC_ESC -> state = if (c == '\\') State.TEXT else State.OSC
            }
        }
        flush(sink)
    }

    fun reset() {
        style = TextStyle.Default
        state = State.TEXT
        params.setLength(0)
        run.setLength(0)
        column = 0
    }

    private fun flush(sink: AnsiSink) {
        if (run.isNotEmpty()) {
            sink.text(run.toString(), style)
            run.setLength(0)
        }
    }

    private fun applySgr(raw: String) {
        if (raw.startsWith("?")) return
        val codes = if (raw.isEmpty()) listOf(0) else raw.split(';', ':').map { it.toIntOrNull() ?: 0 }
        var s = style
        var i = 0
        while (i < codes.size) {
            when (val code = codes[i]) {
                0 -> s = TextStyle.Default
                1 -> s = s.copy(bold = true)
                2 -> s = s.copy(dim = true)
                3 -> s = s.copy(italic = true)
                4 -> s = s.copy(underline = true)
                7 -> s = s.copy(reverse = true)
                22 -> s = s.copy(bold = false, dim = false)
                23 -> s = s.copy(italic = false)
                24 -> s = s.copy(underline = false)
                27 -> s = s.copy(reverse = false)
                in 30..37 -> s = s.copy(foreground = AnsiColor.Indexed(code - 30))
                39 -> s = s.copy(foreground = null)
                in 40..47 -> s = s.copy(background = AnsiColor.Indexed(code - 40))
                49 -> s = s.copy(background = null)
                in 90..97 -> s = s.copy(foreground = AnsiColor.Indexed(code - 90 + 8))
                in 100..107 -> s = s.copy(background = AnsiColor.Indexed(code - 100 + 8))
                38, 48 -> {
                    val (color, used) = extendedColor(codes, i + 1)
                    if (color != null) {
                        s = if (code == 38) s.copy(foreground = color) else s.copy(background = color)
                    }
                    i += used
                }
            }
            i++
        }
        style = s
    }

    /** Reads `5;n` or `2;r;g;b` starting at [start]; returns the color and how many codes it used. */
    private fun extendedColor(codes: List<Int>, start: Int): Pair<AnsiColor?, Int> {
        return when (codes.getOrNull(start)) {
            5 -> {
                val n = codes.getOrNull(start + 1) ?: return null to 1
                (if (n in 0..255) AnsiColor.Indexed(n) else null) to 2
            }
            2 -> {
                if (start + 3 >= codes.size) return null to codes.size - start
                val (r, g, b) = Triple(codes[start + 1], codes[start + 2], codes[start + 3])
                AnsiColor.Rgb(((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)) to 4
            }
            else -> null to 0
        }
    }

    private companion object {
        const val ESC = '\u001B'
    }
}
