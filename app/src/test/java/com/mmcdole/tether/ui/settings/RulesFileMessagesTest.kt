package com.mmcdole.tether.ui.settings

import com.mmcdole.tether.data.rules.ImportSummary
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class RulesFileMessagesTest {
    @Test
    fun fileNameHasTheDate() {
        assertEquals("tether-rules-2026-10-23.json", rulesFileName(LocalDate.of(2026, 10, 23)))
    }

    @Test
    fun importMessageLeavesOutZeroCounts() {
        assertEquals("Imported 12", importMessage(ImportSummary(12, 0, 0, emptyList())))
        assertEquals("Imported 0 · 4 already present", importMessage(ImportSummary(0, 0, 4, emptyList())))
    }

    @Test
    fun importMessageNamesMissingWorlds() {
        assertEquals(
            "Imported 12 · 3 skipped · No world: Bard, Alt",
            importMessage(ImportSummary(12, 3, 0, listOf("Bard", "Alt"))),
        )
    }
}
