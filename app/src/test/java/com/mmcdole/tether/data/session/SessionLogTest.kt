package com.mmcdole.tether.data.session

import com.mmcdole.tether.mud.text.LineContent
import com.mmcdole.tether.mud.text.LineKind
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLogTest {
    private val dir = createTempDirectory("logs").toFile()
    private val clock = Clock.fixed(Instant.parse("2026-10-23T21:14:30Z"), ZoneOffset.UTC)
    private val log = SessionLog(dir, clock)
    private val lines = Scrollback().also { it.listener = log }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun add(text: String, kind: LineKind = LineKind.Output) = lines.add(LineContent(text, emptyList()), kind)

    @Test
    fun fileIsNamedForTheWorldAndStartTime() {
        assertEquals("VikingMUD-2026-10-23-2114.txt", SessionLog.fileName("VikingMUD", LocalDateTime.parse("2026-10-23T21:14:59")))
        assertEquals("VikingMUD – Thorin-2026-01-02-0305.txt", SessionLog.fileName("VikingMUD – Thorin", LocalDateTime.parse("2026-01-02T03:05")))
        assertEquals("a_b_c-2026-01-02-0305.txt", SessionLog.fileName("a/b:c", LocalDateTime.parse("2026-01-02T03:05")))
        log.start("VikingMUD")
        assertEquals(dir.resolve("VikingMUD-2026-10-23-2114.txt"), log.current.value)
    }

    @Test
    fun writesPlainTextOfEveryKind() {
        log.start("VikingMUD")
        lines.add(LineContent("Red dragon", listOf()), LineKind.Output, highlight = 0xFF0000)
        add("look", LineKind.Echo)
        add("Reconnected", LineKind.System)
        add("[reply] tell Hagbard hi", LineKind.Echo)
        log.close()
        assertEquals(listOf("Red dragon", "look", "Reconnected", "[reply] tell Hagbard hi"), log.current.value!!.readLines())
    }

    @Test
    fun promptIsWrittenWithTheCommandAppendedToIt() {
        log.start("VikingMUD")
        val prompt = add("HP > ", LineKind.Prompt)
        lines.replaceLast(prompt.copy(text = "HP > look", echoStart = 5))
        add("A dark room.")
        add("HP > ", LineKind.Prompt)
        log.close()
        assertEquals(listOf("HP > look", "A dark room.", "HP > "), log.current.value!!.readLines())
    }

    @Test
    fun flushWritesWhatsBuffered() {
        log.start("VikingMUD")
        add("one")
        log.flush()
        assertEquals(listOf("one"), log.current.value!!.readLines())
    }

    @Test
    fun nothingIsWrittenBeforeStartOrAfterClose() {
        add("before")
        assertNull(log.current.value)
        assertFalse(log.isOpen)
        log.start("VikingMUD")
        add("during")
        log.close()
        add("after")
        assertEquals(listOf("during"), log.current.value!!.readLines())
    }

    @Test
    fun latestFindsOnlyThatWorldsLogs() {
        dir.resolve("VikingMUD-2026-10-01-1200.txt").apply { writeText("old"); setLastModified(1_000) }
        dir.resolve("VikingMUD-2026-10-02-1200.txt").apply { writeText("new"); setLastModified(2_000) }
        dir.resolve("VikingMUD 2-2026-10-03-1200.txt").apply { writeText("other"); setLastModified(3_000) }
        assertEquals("VikingMUD-2026-10-02-1200.txt", log.latest("VikingMUD")?.name)
        assertNull(log.latest("Other"))
        log.start("VikingMUD")
        assertEquals("VikingMUD-2026-10-23-2114.txt", log.latest("VikingMUD")?.name)
        assertTrue(log.latest("VikingMUD 2") != null)
    }
}
