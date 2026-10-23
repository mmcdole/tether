package com.mmcdole.tether.data.session

import com.mmcdole.tether.mud.text.LineKind
import com.mmcdole.tether.mud.text.StyledLine
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Writes a session's scrollback as plain text, one file per session, in [dir]. It sees exactly
 * what the scrollback gets, so gagged lines and passwords never reach it. Called from the
 * session only, one call at a time.
 */
class SessionLog(private val dir: File, private val clock: Clock) : ScrollbackListener {
    private var writer: BufferedWriter? = null

    // A prompt waits here in case your command is appended to it.
    private var held: StyledLine? = null

    private val _current = MutableStateFlow<File?>(null)

    /** The file of the session being logged, or of the last one logged since the app started. */
    val current: StateFlow<File?> = _current.asStateFlow()

    val isOpen: Boolean get() = writer != null

    /** Starts a new file for [worldName], ending any open one. */
    fun start(worldName: String) {
        close()
        val file = File(dir, fileName(worldName, LocalDateTime.now(clock)))
        writer = try {
            dir.mkdirs()
            BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))
        } catch (e: IOException) {
            null
        }
        if (writer != null) _current.value = file
    }

    override fun added(line: StyledLine) {
        if (writer == null) return
        held?.let(::write)
        held = null
        if (line.kind == LineKind.Prompt && line.echoStart < 0) held = line else write(line)
    }

    override fun replaced(line: StyledLine) {
        if (writer == null || held?.id != line.id) return
        held = null
        write(line)
    }

    fun flush() = guard { flush() }

    /** Writes what's left and closes the file. */
    fun close() {
        held?.let(::write)
        held = null
        guard { close() }
        writer = null
    }

    /** The newest log for [worldName]: this run's, or one left from before. */
    fun latest(worldName: String): File? {
        val pattern = Regex(Regex.escape(safeName(worldName)) + "-\\d{4}-\\d{2}-\\d{2}-\\d{4}\\.txt")
        _current.value?.takeIf { it.exists() && pattern.matches(it.name) }?.let { return it }
        return dir.listFiles { file -> pattern.matches(file.name) }?.maxByOrNull { it.lastModified() }
    }

    private fun write(line: StyledLine) = guard {
        write(line.text)
        newLine()
    }

    // A full disk ends the log rather than the session.
    private inline fun guard(block: BufferedWriter.() -> Unit) {
        val writer = writer ?: return
        try {
            writer.block()
        } catch (e: IOException) {
            this.writer = null
            try {
                writer.close()
            } catch (_: IOException) {
            }
        }
    }

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")

        /** Like `VikingMUD-2026-10-23-2114.txt`. */
        fun fileName(worldName: String, start: LocalDateTime) = "${safeName(worldName)}-${start.format(TIME)}.txt"

        private fun safeName(worldName: String) =
            worldName.replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), "_").trim().take(64).ifEmpty { "world" }
    }
}
