package com.mmcdole.tether.mud.text

import com.mmcdole.tether.mud.ansi.TextStyle

data class StyleSpan(val start: Int, val end: Int, val style: TextStyle)

enum class LineKind {
    /** Text from the MUD. */
    Output,

    /** A prompt from the MUD, possibly with your command echoed after it. */
    Prompt,

    /** A command you sent, shown when there was no prompt to attach it to. */
    Echo,

    /** A message from Tether itself, like "Reconnected". */
    System,
}

/** One line of scrollback: plain text plus the style runs over it. */
data class StyledLine(
    val id: Long,
    val text: String,
    val spans: List<StyleSpan>,
    val kind: LineKind = LineKind.Output,
    /** Set by a highlight trigger: 0xRRGGBB for the whole line. */
    val highlight: Int? = null,
    /** Your command echoed after a prompt; starts at this index in [text]. */
    val echoStart: Int = -1,
)

/** The text and styles of a line before it has an id. */
data class LineContent(val text: String, val spans: List<StyleSpan>) {
    companion object {
        val Empty = LineContent("", emptyList())
    }
}
