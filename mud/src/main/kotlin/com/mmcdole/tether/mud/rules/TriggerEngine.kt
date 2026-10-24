package com.mmcdole.tether.mud.rules

import com.mmcdole.tether.mud.command.CommandSplitter

/** A notification a trigger asked for. */
data class NotifyRequest(
    val triggerId: Long,
    val title: String,
    val message: String,
    /** The trigger's reply template with captures filled in, like `tell Hagbard`; null for no Reply. */
    val replyPrefix: String?,
    val replyRequiresUnlock: Boolean,
)

/** What a Chat trigger puts in the chat tray: the line from [start] on, or [pieces] when it has a Chat text. */
data class ChatCopy(val start: Int, val pieces: List<TemplatePiece>? = null)

/** Everything the matching triggers want done with one line. */
data class TriggerResult(
    val gag: Boolean = false,
    val highlight: Int? = null,
    /** Expanded, in order. These go to the MUD as-is; they are not split again. */
    val commands: List<String> = emptyList(),
    val vibrate: Boolean = false,
    val notifications: List<NotifyRequest> = emptyList(),
    /** From the first matching Chat trigger. */
    val chat: ChatCopy? = null,
) {
    val isEmpty: Boolean get() = this == None

    companion object {
        val None = TriggerResult()
    }
}

object TriggerEngine {
    /** Java regex has no timeout, so regex triggers skip lines longer than this. */
    const val MAX_REGEX_LINE = 4096

    /**
     * Runs every matching trigger on [line], the plain text with colors stripped. A gag doesn't stop
     * the other triggers. [worldName] titles notifications whose pattern has fewer than two captures.
     */
    fun run(rules: RuleSet, line: String, worldName: String): TriggerResult {
        var gag = false
        var chat: ChatCopy? = null
        var highlight: Int? = null
        var vibrate = false
        val commands = ArrayList<String>()
        val notifications = ArrayList<NotifyRequest>()
        val longLine = line.length > MAX_REGEX_LINE

        for (compiled in rules.triggers) {
            if (longLine && compiled.pattern.isRegex) continue
            val match = compiled.pattern.find(line) ?: continue
            val captures = match.captures
            val t = compiled.trigger
            if (t.gag) gag = true
            if (t.chat && chat == null) chat = ChatCopy(match.start, t.chatText?.let { expandPieces(it, match.ranges) })
            if (highlight == null) highlight = t.highlight
            if (t.vibrate) vibrate = true
            // Split before expanding so a `;` in the MUD's text can't become a second command.
            t.send?.let { send -> CommandSplitter.split(send).mapTo(commands) { expand(it, captures) } }
            if (t.notify) notifications += notifyRequest(t, captures, line, worldName)
        }
        if (!gag && highlight == null && commands.isEmpty() && !vibrate && notifications.isEmpty() && chat == null) {
            return TriggerResult.None
        }
        return TriggerResult(gag, highlight, commands, vibrate, notifications, chat)
    }

    private fun notifyRequest(t: Trigger, captures: List<String>, line: String, worldName: String): NotifyRequest {
        // `%1 tells you: %2` → title "Hagbard", message "wanna group?".
        val hasSender = captures.size >= 3
        return NotifyRequest(
            triggerId = t.id,
            title = if (hasSender) captures[1] else worldName,
            message = if (hasSender) captures.last() else line,
            replyPrefix = t.replyTemplate?.let { expand(it, captures) },
            replyRequiresUnlock = t.replyRequiresUnlock,
        )
    }
}
