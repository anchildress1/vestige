package dev.anchildress1.vestige.ui.patterns

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Short, ND-friendly date format for cards + source rows. Avoids the year — pattern cadence is recent. */
private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)

fun formatShortDate(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    SHORT_DATE.format(Instant.ofEpochMilli(epochMs).atZone(zone))

/** Trimmed leading slice of an entry — caps the source snippet so cards stay scannable. */
fun snippetOf(entryText: String, maxLen: Int = MAX_SNIPPET_LEN): String {
    val collapsed = entryText.replace('\n', ' ').trim()
    if (collapsed.length <= maxLen) return collapsed
    val cut = collapsed.substring(0, maxLen).trimEnd()
    return "$cut…"
}

private const val MAX_SNIPPET_LEN = 60
