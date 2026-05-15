package dev.anchildress1.vestige.ui.history

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Fixed clock: 2026-05-14T12:00:00Z (Thursday noon UTC).
 * All test timestamps are relative to this anchor.
 */
class HistoryDateFormatterTest {

    private val zone: ZoneId = ZoneOffset.UTC

    // Thu 2026-05-14 12:00:00 UTC
    private val nowMs: Long = Instant.parse("2026-05-14T12:00:00Z").toEpochMilli()

    // pos — standard relative labels

    @Test
    fun `same calendar day returns Today with time`() {
        val ts = Instant.parse("2026-05-14T09:41:00Z").toEpochMilli()
        assertEquals("Today · 9:41 AM", HistoryDateFormatter.format(ts, nowMs, zone))
    }

    @Test
    fun `previous calendar day returns Yesterday with time`() {
        val ts = Instant.parse("2026-05-13T08:02:00Z").toEpochMilli()
        assertEquals("Yesterday · 8:02 AM", HistoryDateFormatter.format(ts, nowMs, zone))
    }

    @Test
    fun `2 days ago returns short weekday with time`() {
        val ts = Instant.parse("2026-05-12T09:41:00Z").toEpochMilli()
        assertEquals("Tue · 9:41 AM", HistoryDateFormatter.format(ts, nowMs, zone))
    }

    @Test
    fun `6 days ago returns short weekday with time`() {
        val ts = Instant.parse("2026-05-08T09:41:00Z").toEpochMilli()
        assertEquals("Fri · 9:41 AM", HistoryDateFormatter.format(ts, nowMs, zone))
    }

    @Test
    fun `7 days ago returns absolute date with time`() {
        val ts = Instant.parse("2026-05-07T09:41:00Z").toEpochMilli()
        assertEquals("May 7 · 9:41 AM", HistoryDateFormatter.format(ts, nowMs, zone))
    }

    @Test
    fun `30 days ago returns absolute date`() {
        val ts = Instant.parse("2026-04-14T14:30:00Z").toEpochMilli()
        assertEquals("Apr 14 · 2:30 PM", HistoryDateFormatter.format(ts, nowMs, zone))
    }

    // neg — midnight boundary

    @Test
    fun `entry exactly at midnight is still today`() {
        val ts = Instant.parse("2026-05-14T00:00:00Z").toEpochMilli()
        assertEquals("Today · 12:00 AM", HistoryDateFormatter.format(ts, nowMs, zone))
    }

    @Test
    fun `entry 1 second before midnight is yesterday`() {
        val ts = Instant.parse("2026-05-13T23:59:59Z").toEpochMilli()
        assertEquals("Yesterday · 11:59 PM", HistoryDateFormatter.format(ts, nowMs, zone))
    }

    // edge — non-UTC zone

    @Test
    fun `zone with positive offset shifts date boundary correctly`() {
        // nowMs = 2026-05-14T12:00:00Z = 2026-05-14T22:00:00 in UTC+10
        // ts    = 2026-05-14T14:00:00Z = 2026-05-15T00:00:00 in UTC+10 → different calendar day
        val utcPlus10 = ZoneId.of("Australia/Sydney")
        val ts = Instant.parse("2026-05-14T14:00:00Z").toEpochMilli()
        val result = HistoryDateFormatter.format(ts, nowMs, utcPlus10)
        // In UTC+10, ts is one day ahead of now → result must NOT start with "Today"
        assert(!result.startsWith("Today")) {
            "Expected non-Today label in UTC+10 for a timestamp in the next calendar day: $result"
        }
    }

    @Test
    fun `PM time formats correctly`() {
        val ts = Instant.parse("2026-05-14T20:02:00Z").toEpochMilli()
        assertEquals("Today · 8:02 PM", HistoryDateFormatter.format(ts, nowMs, zone))
    }

    // formatSectionHeader

    private val nowDate: LocalDate = LocalDate.of(2026, 5, 14)

    @Test
    fun `section header same day returns TODAY with month-day uppercase`() {
        val date = LocalDate.of(2026, 5, 14)
        assertEquals("TODAY · MAY 14", HistoryDateFormatter.formatSectionHeader(date, nowDate))
    }

    @Test
    fun `section header previous day returns YESTERDAY with month-day uppercase`() {
        val date = LocalDate.of(2026, 5, 13)
        assertEquals("YESTERDAY · MAY 13", HistoryDateFormatter.formatSectionHeader(date, nowDate))
    }

    @Test
    fun `section header older date returns month-day only`() {
        val date = LocalDate.of(2026, 5, 5)
        assertEquals("MAY 5", HistoryDateFormatter.formatSectionHeader(date, nowDate))
    }

    @Test
    fun `section header month boundary returns correct month-day`() {
        val date = LocalDate.of(2026, 4, 30)
        assertEquals("APR 30", HistoryDateFormatter.formatSectionHeader(date, nowDate))
    }

    // formatTimeOnly

    @Test
    fun `formatTimeOnly returns 24-hour HH colon mm format`() {
        val ts = Instant.parse("2026-05-14T09:41:00Z").toEpochMilli()
        assertEquals("09:41", HistoryDateFormatter.formatTimeOnly(ts, zone))
    }

    @Test
    fun `formatTimeOnly afternoon formats correctly`() {
        val ts = Instant.parse("2026-05-14T22:18:00Z").toEpochMilli()
        assertEquals("22:18", HistoryDateFormatter.formatTimeOnly(ts, zone))
    }

    @Test
    fun `formatTimeOnly midnight formats as 00 colon 00`() {
        val ts = Instant.parse("2026-05-14T00:00:00Z").toEpochMilli()
        assertEquals("00:00", HistoryDateFormatter.formatTimeOnly(ts, zone))
    }
}
