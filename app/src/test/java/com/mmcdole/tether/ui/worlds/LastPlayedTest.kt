package com.mmcdole.tether.ui.worlds

import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastPlayedTest {
    private val zone = ZoneId.of("America/New_York")
    private val now = LocalDateTime.of(2026, 10, 22, 9, 0)
    private val clock = Clock.fixed(now.atZone(zone).toInstant(), zone)

    private fun label(then: LocalDateTime) = lastPlayedLabel(then.atZone(zone).toInstant().toEpochMilli(), clock)

    @Test
    fun neverPlayedHasNoLabel() = assertNull(lastPlayedLabel(null, clock))

    @Test
    fun usesCalendarDays() {
        assertEquals("Today", label(now.withHour(0)))
        assertEquals("Yesterday", label(now.minusDays(1).withHour(23)))
        assertEquals("Yesterday", label(now.minusDays(1).withHour(0)))
        assertEquals("2 days ago", label(now.minusDays(2)))
    }

    @Test
    fun weeksMonthsYears() {
        assertEquals("1 week ago", label(now.minusDays(7)))
        assertEquals("3 weeks ago", label(now.minusDays(21)))
        assertEquals("1 month ago", label(now.minusDays(31)))
        assertEquals("11 months ago", label(now.minusMonths(11)))
        assertEquals("1 year ago", label(now.minusMonths(13)))
        assertEquals("3 years ago", label(now.minusYears(3)))
    }
}
