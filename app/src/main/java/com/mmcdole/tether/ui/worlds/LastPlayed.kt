package com.mmcdole.tether.ui.worlds

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** "Today", "Yesterday", "3 weeks ago", by calendar day in the clock's zone. Null if never played. */
fun lastPlayedLabel(lastConnectedAt: Long?, clock: Clock): String? {
    if (lastConnectedAt == null) return null
    val then = Instant.ofEpochMilli(lastConnectedAt).atZone(clock.zone).toLocalDate()
    val today = LocalDate.now(clock)
    val days = ChronoUnit.DAYS.between(then, today)
    return when {
        days <= 0 -> "Today"
        days == 1L -> "Yesterday"
        days < 7 -> "$days days ago"
        days < 30 -> ago(days / 7, "week")
        else -> {
            val months = ChronoUnit.MONTHS.between(then, today)
            if (months < 12) ago(maxOf(months, 1), "month") else ago(months / 12, "year")
        }
    }
}

private fun ago(count: Long, unit: String) = if (count == 1L) "1 $unit ago" else "$count ${unit}s ago"
