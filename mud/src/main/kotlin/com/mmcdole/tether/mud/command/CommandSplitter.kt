package com.mmcdole.tether.mud.command

/**
 * Splits typed input into commands on `;`. `\;` is a literal semicolon; other backslashes are kept.
 * Text inside `{...}` is kept as-is, so `#alias k {kill %1;grin}` stays one command.
 */
object CommandSplitter {
    fun split(input: String): List<String> {
        val commands = ArrayList<String>()
        val current = StringBuilder()
        var depth = 0
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c == '{' -> { depth++; current.append(c) }
                c == '}' && depth > 0 -> { depth--; current.append(c) }
                depth > 0 -> current.append(c)
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
