package com.mmcdole.tether.mud.command

/**
 * Expands speedwalks like `10e`, `3n2e` or `eeennn` into one direction per command. Directions are
 * `n e s w u d`. So that words made of those letters (`news`, `use`, `seen`) still go to the MUD, a
 * speedwalk needs a count somewhere or every letter repeated, like `ee` or `nnwww`.
 */
object Speedwalk {
    /** A single count above this isn't a speedwalk, so a typo can't send hundreds of moves. */
    const val MAX_COUNT = 99

    private const val DIRECTIONS = "neswud"

    /** Returns the directions to send, or null if [command] isn't a speedwalk. */
    fun expand(command: String): List<String>? {
        if (command.length < 2) return null
        val moves = ArrayList<String>()
        var counted = false
        var i = 0
        while (i < command.length) {
            val start = i
            while (i < command.length && command[i].isDigit()) i++
            val dir = command.getOrNull(i) ?: return null
            if (dir !in DIRECTIONS) return null
            val count = if (i > start) {
                counted = true
                command.substring(start, i).toIntOrNull()?.takeIf { it in 1..MAX_COUNT } ?: return null
            } else {
                1
            }
            repeat(count) { moves += dir.toString() }
            i++
        }
        if (!counted && !allRepeated(command)) return null
        return moves
    }

    /** True if every letter in [text] sits next to the same letter, like `eeennn` but not `need`. */
    private fun allRepeated(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            var end = i
            while (end < text.length && text[end] == text[i]) end++
            if (end - i < 2) return false
            i = end
        }
        return true
    }
}
