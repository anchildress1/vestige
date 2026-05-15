package dev.anchildress1.vestige.ui.history

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

object HistoryDateFormatter {

    /**
     * Returns a concise date+time string relative to [nowEpochMs]:
     * - Same calendar day → `Today · 9:41 AM`
     * - Previous day → `Yesterday · 8:02 PM`
     * - 2–6 days ago → `Mon · 9:41 AM`
     * - Older → `May 7 · 9:41 AM`
     */
    fun format(timestampEpochMs: Long, nowEpochMs: Long, zoneId: ZoneId): String {
        val tsZdt = Instant.ofEpochMilli(timestampEpochMs).atZone(zoneId)
        val nowZdt = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId)
        val tsDate = tsZdt.toLocalDate()
        val nowDate = nowZdt.toLocalDate()
        val daysDiff = ChronoUnit.DAYS.between(tsDate, nowDate)
        val timeStr = tsZdt.format(TIME_FORMATTER)
        return when {
            daysDiff == 0L -> "Today · $timeStr"
            daysDiff == 1L -> "Yesterday · $timeStr"
            daysDiff <= WITHIN_WEEK_DAYS -> "${tsZdt.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US)} · $timeStr"
            else -> "${tsZdt.format(DATE_FORMATTER)} · $timeStr"
        }
    }

    /**
     * Returns a section header label for a date group in the history list:
     * - Same calendar day → `TODAY · MAY 8`
     * - Previous day → `YESTERDAY · MAY 7`
     * - Older → `MAY 5`
     */
    fun formatSectionHeader(date: LocalDate, nowDate: LocalDate): String {
        val daysDiff = ChronoUnit.DAYS.between(date, nowDate)
        val monthDay = date.format(SECTION_DATE_FORMATTER).uppercase(Locale.US)
        return when {
            daysDiff == 0L -> "TODAY · $monthDay"
            daysDiff == 1L -> "YESTERDAY · $monthDay"
            else -> monthDay
        }
    }

    /** Returns the time-of-day in 24-hour `HH:mm` format for the history row left column. */
    fun formatTimeOnly(timestampEpochMs: Long, zoneId: ZoneId): String =
        Instant.ofEpochMilli(timestampEpochMs).atZone(zoneId).format(TIME_ONLY_FORMATTER)

    private const val WITHIN_WEEK_DAYS = 6L
    private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val SECTION_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val TIME_ONLY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
}
