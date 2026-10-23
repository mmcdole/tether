package com.mmcdole.tether.ui.rules

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.mmcdole.tether.mud.rules.Alias
import com.mmcdole.tether.mud.rules.Pattern
import com.mmcdole.tether.mud.rules.PatternResult
import com.mmcdole.tether.mud.rules.Scope
import com.mmcdole.tether.mud.rules.Trigger
import com.mmcdole.tether.ui.theme.AnsiColors

enum class RuleTab { Triggers, Aliases }

/** The highlight choices: the six bright ANSI colors. */
enum class HighlightColor(val label: String, ansiIndex: Int) {
    Red("red", 9),
    Green("green", 10),
    Yellow("yellow", 11),
    Blue("blue", 12),
    Magenta("magenta", 13),
    Cyan("cyan", 14),
    ;

    val color: Color = AnsiColors.palette[ansiIndex]
    val rgb: Int = color.toArgb() and 0xFFFFFF

    companion object {
        fun of(rgb: Int?): HighlightColor? = entries.firstOrNull { it.rgb == rgb }
    }
}

/** The row's second line, like "Highlight red · vibrate" or "kill %1". */
fun Trigger.summary(): String {
    val command = send?.takeIf { it.isNotBlank() }
    val parts = listOfNotNull(
        "gag".takeIf { gag },
        highlight?.let { HighlightColor.of(it)?.let { c -> "highlight ${c.label}" } ?: "highlight" },
        command,
        "vibrate".takeIf { vibrate },
        "notify".takeIf { notify },
    )
    val text = parts.joinToString(" · ")
    // The command is shown as typed; only the app's own words get capitalized.
    val startsWithCommand = command != null && !gag && highlight == null
    return if (startsWithCommand) text else text.replaceFirstChar { it.uppercase() }
}

fun Alias.summary(): String = expansion

/** Short form of a [PatternResult.Invalid] message, or null when there's nothing worth showing. */
internal fun patternError(pattern: String, isRegex: Boolean): String? {
    if (pattern.isEmpty()) return null
    val result = Pattern.compile(pattern, isRegex) as? PatternResult.Invalid ?: return null
    return shortPatternMessage(result.message)
}

/** "Invalid regex: Incorrectly nested parentheses in regexp pattern at position 13" → "Incorrectly nested parentheses at 13". */
internal fun shortPatternMessage(message: String): String =
    message.removePrefix("Invalid regex: ")
        .replace(Regex("\\s+in (the )?(regexp|regular expression) pattern", RegexOption.IGNORE_CASE), "")
        .replace(" at position ", " at ")

data class TriggerForm(
    val id: Long = 0,
    val scope: Scope,
    val pattern: String = "",
    val isRegex: Boolean = false,
    val highlight: Int? = null,
    val gag: Boolean = false,
    val vibrate: Boolean = false,
    val notify: Boolean = false,
    val send: String = "",
    // Not editable yet; carried through so saving keeps the stored values.
    val group: String = "",
    val priority: Int = 0,
    val replyTemplate: String = "",
    val replyRequiresUnlock: Boolean = true,
    val enabled: Boolean = true,
    val advanced: Boolean = false,
) {
    val isNew get() = id == 0L
    val patternError get() = patternError(pattern, isRegex)
    private val doesSomething get() = highlight != null || gag || vibrate || notify || send.isNotBlank()
    val canSave get() = pattern.isNotEmpty() && patternError == null && doesSomething

    fun toTrigger() = Trigger(
        id = id,
        scope = scope,
        pattern = pattern,
        isRegex = isRegex,
        enabled = enabled,
        group = group.trim().ifEmpty { null },
        priority = priority,
        highlight = highlight,
        gag = gag,
        send = send.trim().ifEmpty { null },
        vibrate = vibrate,
        notify = notify,
        replyTemplate = replyTemplate.trim().ifEmpty { null }?.takeIf { notify },
        replyRequiresUnlock = replyRequiresUnlock,
    )

    companion object {
        fun of(t: Trigger) = TriggerForm(
            id = t.id,
            scope = t.scope,
            pattern = t.pattern,
            isRegex = t.isRegex,
            highlight = t.highlight,
            gag = t.gag,
            vibrate = t.vibrate,
            notify = t.notify,
            send = t.send.orEmpty(),
            group = t.group.orEmpty(),
            priority = t.priority,
            replyTemplate = t.replyTemplate.orEmpty(),
            replyRequiresUnlock = t.replyRequiresUnlock,
            enabled = t.enabled,
            // Open Advanced when something in it is set, so nothing is hidden while editing.
            advanced = t.isRegex || !t.send.isNullOrBlank() ||
                t.replyTemplate != null || !t.replyRequiresUnlock,
        )
    }
}

data class AliasForm(
    val id: Long = 0,
    val scope: Scope,
    val name: String = "",
    val expansion: String = "",
    val group: String = "",
    val enabled: Boolean = true,
    /** Names already used in each scope, other than this alias's own. */
    val taken: Map<Scope, Set<String>> = emptyMap(),
) {
    val isNew get() = id == 0L
    val nameError get() = if (name in taken[scope].orEmpty()) "Already used" else null
    val canSave get() = name.isNotEmpty() && nameError == null && expansion.isNotBlank()

    fun toAlias() = Alias(id, scope, name, expansion.trim(), enabled, group.trim().ifEmpty { null })

    companion object {
        fun of(a: Alias, taken: Map<Scope, Set<String>>) =
            AliasForm(a.id, a.scope, a.name, a.expansion, a.group.orEmpty(), a.enabled, taken)
    }
}
