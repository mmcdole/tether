package com.mmcdole.tether.mud.rules

import com.mmcdole.tether.mud.command.CommandSplitter

sealed interface AliasResult {
    data class Commands(val commands: List<String>) : AliasResult

    data class Error(val message: String) : AliasResult
}

object AliasExpander {
    /** How many aliases may expand inside one another before it's treated as a loop. */
    const val MAX_DEPTH = 10

    /**
     * Expands [command] if its first word is an alias. `%1`–`%9` are the space-separated arguments and
     * `%0` is everything after the name; with no `%N` in the expansion the arguments are appended. A
     * `;` in the expansion makes several commands, and each is expanded again.
     */
    fun expand(command: String, aliases: Map<String, Alias>): AliasResult {
        if (aliases.isEmpty()) return AliasResult.Commands(listOf(command))
        val out = ArrayList<String>()
        return expandInto(command, aliases, 0, out) ?: AliasResult.Commands(out)
    }

    private fun expandInto(command: String, aliases: Map<String, Alias>, depth: Int, out: MutableList<String>): AliasResult.Error? {
        val trimmed = command.trimStart()
        val name = trimmed.substringBefore(' ')
        val alias = aliases[name]
        if (alias == null) {
            out += command
            return null
        }
        if (depth == MAX_DEPTH) return AliasResult.Error("Alias loop: more than $MAX_DEPTH levels deep at \"$name\"")

        val rest = trimmed.substring(name.length).trimStart()
        val args = rest.split(' ').filter { it.isNotEmpty() }
        val captures = listOf(rest) + args
        val appendArgs = !usesCaptures(alias.expansion) && rest.isNotEmpty()

        // Split before substituting so an argument's `;` (typed as `\;`) stays literal.
        val pieces = CommandSplitter.split(alias.expansion)
        for ((i, piece) in pieces.withIndex()) {
            var expanded = expand(piece, captures)
            if (appendArgs && i == pieces.lastIndex) expanded = if (expanded.isEmpty()) rest else "$expanded $rest"
            expandInto(expanded, aliases, depth + 1, out)?.let { return it }
        }
        return null
    }
}
