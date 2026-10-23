package com.mmcdole.tether.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

/** Time since [since] as "4h 12m", updated each minute. */
@Composable
fun uptime(since: Instant?): String {
    val text by produceState("", since) {
        if (since == null) return@produceState
        while (true) {
            val elapsed = Duration.between(since, Instant.now())
            value = formatUptime(elapsed)
            delay(60_000 - elapsed.toMillis() % 60_000)
        }
    }
    return text
}

internal fun formatUptime(elapsed: Duration): String {
    val minutes = elapsed.toMinutes().coerceAtLeast(0)
    return when {
        minutes >= 24 * 60 -> "${minutes / (24 * 60)}d ${minutes / 60 % 24}h"
        minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
        else -> "${minutes}m"
    }
}
