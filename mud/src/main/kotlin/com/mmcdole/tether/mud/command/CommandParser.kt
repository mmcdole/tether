package com.mmcdole.tether.mud.command

import com.mmcdole.tether.mud.rules.Alias
import com.mmcdole.tether.mud.rules.AliasExpander
import com.mmcdole.tether.mud.rules.AliasResult

/** One thing to do for a line of input. The parser only describes steps; it doesn't store anything. */
sealed interface Step {
    data class Send(val command: String) : Step

    /** [global] is true for `#galias`: all worlds instead of this one. */
    data class AddAlias(val name: String, val expansion: String, val global: Boolean) : Step

    /** A trigger that sends [command] when [pattern] matches. */
    data class AddAction(val pattern: String, val command: String, val global: Boolean) : Step

    data class AddGag(val pattern: String, val global: Boolean) : Step

    /** Remove the world alias with this name if there is one, otherwise the global one. */
    data class RemoveAlias(val name: String) : Step

    /** Remove the world send trigger with this pattern if there is one, otherwise the global one. */
    data class RemoveAction(val pattern: String) : Step

    /** Remove the world gag with this pattern if there is one, otherwise the global one. */
    data class RemoveGag(val pattern: String) : Step

    data object ListAliases : Step

    data object ListTriggers : Step

    data class Error(val message: String) : Step
}

/**
 * Turns a line of input into [Step]s. Splits on `;` (not inside braces, and not `\;`), expands
 * aliases, and parses `#` commands. Arguments to `#` commands go in braces, which may nest; braces are
 * optional for one word, and an unbraced last argument takes the rest of the line.
 */
object CommandParser {
    fun parse(input: String, aliases: Map<String, Alias> = emptyMap()): List<Step> =
        CommandSplitter.split(input).flatMap { parseCommand(it, aliases) }

    /** Parses one command that is already split, such as a command a trigger sends. */
    fun parseCommand(command: String, aliases: Map<String, Alias> = emptyMap()): List<Step> {
        if (isLocal(command)) return listOf(local(command.trimStart()))
        return when (val result = AliasExpander.expand(command, aliases)) {
            is AliasResult.Error -> listOf(Step.Error(result.message))
            is AliasResult.Commands -> result.commands.map { if (isLocal(it)) local(it.trimStart()) else Step.Send(it) }
        }
    }

    private fun isLocal(command: String) = command.trimStart().startsWith('#')

    private fun local(command: String): Step {
        val name = command.drop(1).takeWhile { !it.isWhitespace() && it != '{' }
        val rest = command.substring(1 + name.length)
        val usage = { args: String -> Step.Error("Usage: #$name $args") }
        return when (val lower = name.lowercase()) {
            "alias", "galias" -> args(rest, 2) { a ->
                when {
                    a.isEmpty() -> Step.ListAliases
                    a.size < 2 || a[0].isEmpty() || a[1].isEmpty() -> usage("{name} {expansion}")
                    a[0].any { it.isWhitespace() } -> Step.Error("Alias name can't contain spaces: ${a[0]}")
                    else -> Step.AddAlias(a[0], a[1], global = lower == "galias")
                }
            }
            "action", "gaction" -> args(rest, 2) { a ->
                when {
                    a.isEmpty() -> Step.ListTriggers
                    a.size < 2 || a[0].isEmpty() || a[1].isEmpty() -> usage("{pattern} {command}")
                    else -> Step.AddAction(a[0], a[1], global = lower == "gaction")
                }
            }
            "gag", "ggag" -> one(rest, usage("{pattern}")) { Step.AddGag(it, global = lower == "ggag") }
            "unalias" -> one(rest, usage("{name}")) { Step.RemoveAlias(it) }
            "unaction" -> one(rest, usage("{pattern}")) { Step.RemoveAction(it) }
            "ungag" -> one(rest, usage("{pattern}")) { Step.RemoveGag(it) }
            else -> Step.Error("Unknown command: #$name")
        }
    }

    private fun one(text: String, usage: Step, make: (String) -> Step): Step =
        args(text, 1) { a -> if (a.isEmpty() || a[0].isEmpty()) usage else make(a[0]) }

    /** Reads up to [max] arguments from [text] and passes them to [make], or returns a parse error. */
    private fun args(text: String, max: Int, make: (List<String>) -> Step): Step {
        val args = ArrayList<String>()
        var i = 0
        fun skipSpace() { while (i < text.length && text[i].isWhitespace()) i++ }
        while (args.size < max) {
            skipSpace()
            if (i >= text.length) break
            if (text[i] == '{') {
                val close = matchingBrace(text, i) ?: return Step.Error("Missing } in: ${text.trim()}")
                args += text.substring(i + 1, close)
                i = close + 1
            } else if (args.size == max - 1) {
                args += text.substring(i).trimEnd()
                i = text.length
            } else {
                val start = i
                while (i < text.length && !text[i].isWhitespace()) i++
                args += text.substring(start, i)
            }
        }
        skipSpace()
        if (i < text.length) return Step.Error("Too many arguments: ${text.substring(i)}")
        return make(args)
    }

    private fun matchingBrace(text: String, open: Int): Int? {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        return null
    }
}
