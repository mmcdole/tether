package com.mmcdole.tether.mud.rules

/** Where a rule applies: every world, or one world (one character). */
sealed interface Scope {
    data object Global : Scope

    data class World(val worldId: Long) : Scope
}

/** A line pattern plus what to do when it matches. Actions are flat fields so they store and edit easily. */
data class Trigger(
    val id: Long,
    val scope: Scope,
    val pattern: String,
    val isRegex: Boolean = false,
    val enabled: Boolean = true,
    val group: String? = null,
    /** Lower runs first. */
    val priority: Int = 0,
    /** 0xRRGGBB for the whole line. */
    val highlight: Int? = null,
    val gag: Boolean = false,
    /** Commands to send; may contain `;` and `%N`. */
    val send: String? = null,
    val vibrate: Boolean = false,
    val notify: Boolean = false,
    /** Put in front of a notification reply, like `tell %1`. */
    val replyTemplate: String? = null,
    val replyRequiresUnlock: Boolean = true,
    /** Copy the line to the chat tray. */
    val chat: Boolean = false,
)

/** Hides lines that match [pattern]. */
data class Gag(
    val id: Long,
    val scope: Scope,
    val pattern: String,
    val isRegex: Boolean = false,
    val enabled: Boolean = true,
    val group: String? = null,
)

/** Sends [command] every [intervalMillis] while connected and [enabled]. */
data class Timer(
    val id: Long,
    val scope: Scope,
    val name: String,
    /** May contain `;` and aliases. */
    val command: String,
    val intervalMillis: Long,
    val enabled: Boolean = true,
    val group: String? = null,
)

/** A timer interval as `1s` or `2.5s`. */
fun secondsText(millis: Long): String = if (millis % 1000 == 0L) "${millis / 1000}s" else "${millis / 1000.0}s"

/** Replaces the first word of a command, like `k` → `kill %1`. */
data class Alias(
    val id: Long,
    val scope: Scope,
    val name: String,
    val expansion: String,
    val enabled: Boolean = true,
    val group: String? = null,
)
