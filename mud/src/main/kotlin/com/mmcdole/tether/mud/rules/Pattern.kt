package com.mmcdole.tether.mud.rules

import java.util.regex.PatternSyntaxException

/** Where a pattern matched, and its captures with the whole match as `%0`. */
class PatternMatch(val start: Int, val captures: List<String>)

sealed interface PatternResult {
    data class Valid(val pattern: Pattern) : PatternResult

    data class Invalid(val message: String) : PatternResult
}

/**
 * A compiled trigger pattern. Plain patterns are literal text with `%1`–`%9` wildcards that match
 * anywhere in the line; a leading `^` anchors to the start and a trailing `$` to the end. `%%` is a
 * literal `%`. Regex patterns are used as-is and their groups become `%1`, `%2`…
 */
class Pattern private constructor(
    val source: String,
    val isRegex: Boolean,
    private val regex: Regex,
    /** For plain patterns: the group index of each wildcard number, 0 if unused. */
    private val wildcardGroups: IntArray?,
) {
    /** Returns the captures, with the whole match as `%0`, or null if the line doesn't match. */
    fun match(text: String): List<String>? = find(text)?.captures

    /** Like [match], plus where in [text] the match starts. */
    fun find(text: String): PatternMatch? {
        val m = regex.find(text) ?: return null
        val groups = wildcardGroups ?: return PatternMatch(m.range.first, m.groupValues)
        val captures = List(groups.size) { n ->
            if (n == 0) m.value else if (groups[n] == 0) "" else m.groupValues[groups[n]]
        }
        return PatternMatch(m.range.first, captures)
    }

    override fun toString() = source

    companion object {
        fun compile(source: String, isRegex: Boolean = false): PatternResult {
            if (source.isEmpty()) return PatternResult.Invalid("Pattern is empty")
            if (!isRegex) return plain(source)
            return try {
                PatternResult.Valid(Pattern(source, true, Regex(source), null))
            } catch (e: PatternSyntaxException) {
                val at = if (e.index >= 0) " at position ${e.index + 1}" else ""
                PatternResult.Invalid("Invalid regex: ${e.description}$at")
            }
        }

        private fun plain(source: String): PatternResult {
            val anchorStart = source.startsWith("^")
            val anchorEnd = source.endsWith("$") && source.length > (if (anchorStart) 1 else 0)
            val body = source.substring(if (anchorStart) 1 else 0, source.length - if (anchorEnd) 1 else 0)
            val tokens = tokenize(body)

            val regex = StringBuilder()
            if (anchorStart) regex.append('^')
            val groups = IntArray(10)
            var groupCount = 0
            var maxWildcard = 0
            tokens.forEachIndexed { i, token ->
                when (token) {
                    is Token.Text -> regex.append(Regex.escape(token.text))
                    is Token.Wildcard -> {
                        val n = token.n
                        maxWildcard = maxOf(maxWildcard, n)
                        if (groups[n] != 0) {
                            // The same wildcard twice must match the same text.
                            regex.append("\\").append(groups[n])
                        } else {
                            groups[n] = ++groupCount
                            // A wildcard at the very end of an unanchored pattern takes the rest of the line.
                            val rest = i == tokens.lastIndex && !anchorEnd
                            regex.append(if (rest) "(.*)" else "(.*?)")
                        }
                    }
                }
            }
            if (anchorEnd) regex.append('$')
            return PatternResult.Valid(Pattern(source, false, Regex(regex.toString()), groups.copyOf(maxWildcard + 1)))
        }

        private sealed interface Token {
            data class Text(val text: String) : Token

            data class Wildcard(val n: Int) : Token
        }

        private fun tokenize(body: String): List<Token> {
            val tokens = ArrayList<Token>()
            val text = StringBuilder()
            var i = 0
            while (i < body.length) {
                val c = body[i]
                val next = body.getOrNull(i + 1)
                when {
                    c == '%' && next == '%' -> { text.append('%'); i++ }
                    c == '%' && next != null && next in '1'..'9' -> {
                        if (text.isNotEmpty()) { tokens += Token.Text(text.toString()); text.setLength(0) }
                        tokens += Token.Wildcard(next - '0')
                        i++
                    }
                    else -> text.append(c)
                }
                i++
            }
            if (text.isNotEmpty()) tokens += Token.Text(text.toString())
            return tokens
        }
    }
}

/** Substitutes `%0`–`%9` from [captures] (missing ones become empty) and `%%` with `%`. */
fun expand(template: String, captures: List<String>): String {
    if ('%' !in template) return template
    val out = StringBuilder()
    var i = 0
    while (i < template.length) {
        val c = template[i]
        val next = template.getOrNull(i + 1)
        when {
            c == '%' && next == '%' -> { out.append('%'); i++ }
            c == '%' && next != null && next in '0'..'9' -> { out.append(captures.getOrElse(next - '0') { "" }); i++ }
            else -> out.append(c)
        }
        i++
    }
    return out.toString()
}

/** True if [template] uses any of `%0`–`%9`. */
internal fun usesCaptures(template: String): Boolean {
    var i = 0
    while (i < template.length - 1) {
        if (template[i] == '%') {
            val next = template[i + 1]
            if (next in '0'..'9') return true
            if (next == '%') i++
        }
        i++
    }
    return false
}
