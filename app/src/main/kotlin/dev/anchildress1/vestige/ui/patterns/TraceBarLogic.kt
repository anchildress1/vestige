package dev.anchildress1.vestige.ui.patterns

import dev.anchildress1.vestige.storage.EntryEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Bucket supporting-entry timestamps into the trailing [days]-day window ending at [asOfMs].
 * Index 0 is the oldest day in the window; `days - 1` is today (matches the POC tokens.jsx
 * `TraceBar` convention where lit bars stack newest-on-the-right).
 *
 * Entries outside the window are dropped. Multiple entries on the same day collapse into one lit
 * bar — the visualization shows recurrence rhythm, not raw count.
 */
fun traceBarHits(
    supportingTimestampsMs: List<Long>,
    asOfMs: Long,
    days: Int = TRACE_BAR_DEFAULT_DAYS,
    zone: ZoneId = ZoneId.systemDefault(),
): Set<Int> {
    require(days > 0) { "traceBarHits days must be > 0 (got $days)" }
    val today = Instant.ofEpochMilli(asOfMs).atZone(zone).toLocalDate()
    val oldest = today.minusDays((days - 1).toLong())
    return supportingTimestampsMs.mapNotNullTo(mutableSetOf()) { ms ->
        val entryDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        if (entryDate.isBefore(oldest) || entryDate.isAfter(today)) {
            null
        } else {
            ChronoUnit.DAYS.between(oldest, entryDate).toInt()
        }
    }
}

/** Convenience overload — pull the timestamps off [EntryEntity] without the call-site mapping. */
fun traceBarHitsFromEntries(
    entries: List<EntryEntity>,
    asOfMs: Long,
    days: Int = TRACE_BAR_DEFAULT_DAYS,
    zone: ZoneId = ZoneId.systemDefault(),
): Set<Int> = traceBarHits(entries.map { it.timestampEpochMs }, asOfMs, days, zone)

/** Days the POC uses for both the card glyph and the detail-screen hero strip. */
const val TRACE_BAR_DEFAULT_DAYS: Int = 30

/** Visual sugar — kept here so the helper and composable read the same single source of truth. */
fun isHit(hits: Set<Int>, index: Int): Boolean = index in hits

/** Pure check — true when at least one entry landed in the trailing window. Used by VMs/tests. */
fun hasRecentHits(hits: Set<Int>): Boolean = hits.isNotEmpty()

/** Used for variants like LocalDate-based test fixtures so callers don't reach for `Instant` boilerplate. */
fun localDateToEpochMillis(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
    date.atStartOfDay(zone).toInstant().toEpochMilli()
