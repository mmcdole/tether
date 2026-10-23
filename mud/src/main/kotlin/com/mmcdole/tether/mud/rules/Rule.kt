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
)

/** Replaces the first word of a command, like `k` → `kill %1`. */
data class Alias(
    val id: Long,
    val scope: Scope,
    val name: String,
    val expansion: String,
    val enabled: Boolean = true,
    val group: String? = null,
)
