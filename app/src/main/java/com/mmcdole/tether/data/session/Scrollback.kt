package com.mmcdole.tether.data.session

import com.mmcdole.tether.mud.text.LineContent
import com.mmcdole.tether.mud.text.LineKind
import com.mmcdole.tether.mud.text.StyledLine

/** What the screen shows: finished lines, oldest first, then the partial line if there is one. */
class ScrollbackSnapshot(val lines: List<StyledLine>) {
    companion object {
        val Empty = ScrollbackSnapshot(emptyList())
    }
}

/**
 * The last [capacity] lines of a session. Ids only ever increase, even across [clear],
 * so the screen can key lines by id.
 */
class Scrollback(capacity: Int = DEFAULT_CAPACITY) {
    private val lines = ArrayDeque<StyledLine>()

    /** Lowering it drops the oldest lines. */
    var capacity: Int = capacity
        set(value) {
            require(value > 0)
            field = value
            while (lines.size > value) lines.removeFirst()
        }

    /** The id the next line will get. The partial line borrows it, so it keeps its id once finished. */
    var nextId = 1L
        private set

    val last: StyledLine? get() = lines.lastOrNull()

    fun add(content: LineContent, kind: LineKind = LineKind.Output, echoStart: Int = -1, highlight: Int? = null): StyledLine {
        val line = StyledLine(nextId++, content.text, content.spans, kind, highlight, echoStart)
        if (lines.size >= capacity) lines.removeFirst()
        lines.addLast(line)
        return line
    }

    /** Replaces the newest line, keeping its id. */
    fun replaceLast(line: StyledLine) {
        check(line.id == lines.last().id)
        lines[lines.lastIndex] = line
    }

    fun clear() = lines.clear()

    fun snapshot(partial: LineContent = LineContent.Empty): ScrollbackSnapshot {
        val list = ArrayList<StyledLine>(lines.size + 1)
        list.addAll(lines)
        if (partial.text.isNotEmpty()) list += StyledLine(nextId, partial.text, partial.spans)
        return ScrollbackSnapshot(list)
    }

    companion object {
        const val DEFAULT_CAPACITY = 5000
    }
}
