package com.mmcdole.tether.data.session

/** What happened to a reply from a notification. */
enum class ReplyResult {
    Sent,

    /** The session is reconnecting; it goes out once it's back and logged in. */
    Queued,

    /** No session for that world, so nothing was sent. */
    NotConnected,
}

/**
 * The command a notification reply sends: `tell Hagbard` and `sure, omw` give
 * `tell Hagbard sure, omw`. Line breaks become spaces, since a reply is one line of chat.
 * Null when nothing was typed.
 */
fun replyCommand(prefix: String, text: String): String? {
    val body = text.trim().replace(LINE_BREAKS, " ")
    if (body.isEmpty()) return null
    val start = prefix.trim()
    return if (start.isEmpty()) body else "$start $body"
}

private val LINE_BREAKS = Regex("\\s*[\\r\\n]+\\s*")
