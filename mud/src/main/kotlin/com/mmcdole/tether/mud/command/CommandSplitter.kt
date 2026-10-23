package com.mmcdole.tether.mud.command

/** Splits typed input into commands on `;`. `\;` is a literal semicolon; other backslashes are kept. */
object CommandSplitter {
    fun split(input: String): List<String> {
        val commands = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c == '\\' && input.getOrNull(i + 1) == ';' -> { current.append(';'); i++ }
                c == ';' -> { commands += current.toString(); current.setLength(0) }
                else -> current.append(c)
            }
            i++
        }
        commands += current.toString()
        return commands
    }
}
