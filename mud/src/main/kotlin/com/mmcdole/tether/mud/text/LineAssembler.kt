package com.mmcdole.tether.mud.text

import com.mmcdole.tether.mud.ansi.AnsiSink
import com.mmcdole.tether.mud.ansi.TextStyle

/**
 * Collects styled runs into lines. Finished lines go to [onLine]. Text after the last
 * newline stays as the partial line, which is usually a prompt.
 */
class LineAssembler(private val onLine: (LineContent) -> Unit) : AnsiSink {
    private val text = StringBuilder()
    private val spans = ArrayList<StyleSpan>()

    override fun text(text: String, style: TextStyle) {
        val start = this.text.length
        this.text.append(text)
        val last = spans.lastOrNull()
        if (last != null && last.style == style && last.end == start) {
            spans[spans.lastIndex] = last.copy(end = this.text.length)
        } else if (style != TextStyle.Default) {
            spans += StyleSpan(start, this.text.length, style)
        }
    }

    override fun newline() {
        onLine(take())
    }

    val hasPartial: Boolean get() = text.isNotEmpty()

    fun peekPartial(): LineContent = LineContent(text.toString(), spans.toList())

    /** Returns the partial line and clears it. */
    fun take(): LineContent {
        val line = LineContent(text.toString(), spans.toList())
        text.setLength(0)
        spans.clear()
        return line
    }

    fun reset() {
        text.setLength(0)
        spans.clear()
    }
}
