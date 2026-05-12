package dev.anchildress1.vestige.ui.patterns

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset

class TraceBarLogicTest {

    private val zone = ZoneOffset.UTC

    // region traceBarHits

    @Test
    fun `today maps to the last index in the window`() {
        val today = LocalDate.parse("2026-05-12")
        val hits = traceBarHits(
            supportingTimestampsMs = listOf(localDateToEpochMillis(today, zone)),
            asOfMs = localDateToEpochMillis(today, zone),
            days = 30,
            zone = zone,
        )
        assertEquals(setOf(29), hits)
    }

    @Test
    fun `the oldest day maps to index zero`() {
        val today = LocalDate.parse("2026-05-12")
        val oldest = today.minusDays(29)
        val hits = traceBarHits(
            supportingTimestampsMs = listOf(localDateToEpochMillis(oldest, zone)),
            asOfMs = localDateToEpochMillis(today, zone),
            zone = zone,
        )
        assertEquals(setOf(0), hits)
    }

    @Test
    fun `multiple entries on the same day collapse into a single lit index`() {
        val today = LocalDate.parse("2026-05-12")
        val ms = localDateToEpochMillis(today, zone)
        val hits = traceBarHits(
            supportingTimestampsMs = listOf(ms, ms, ms),
            asOfMs = ms,
            zone = zone,
        )
        assertEquals(setOf(29), hits)
    }

    @Test
    fun `entries older than the window drop out`() {
        val today = LocalDate.parse("2026-05-12")
        val tooOld = today.minusDays(30)
        val hits = traceBarHits(
            supportingTimestampsMs = listOf(localDateToEpochMillis(tooOld, zone)),
            asOfMs = localDateToEpochMillis(today, zone),
            zone = zone,
        )
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `entries newer than asOf also drop out`() {
        val today = LocalDate.parse("2026-05-12")
        val future = today.plusDays(1)
        val hits = traceBarHits(
            supportingTimestampsMs = listOf(localDateToEpochMillis(future, zone)),
            asOfMs = localDateToEpochMillis(today, zone),
            zone = zone,
        )
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `mixed inside-and-outside-the-window entries keep only the inside ones`() {
        val today = LocalDate.parse("2026-05-12")
        val asOf = localDateToEpochMillis(today, zone)
        val inWindow = localDateToEpochMillis(today.minusDays(7), zone)
        val tooOld = localDateToEpochMillis(today.minusDays(40), zone)
        val tooNew = localDateToEpochMillis(today.plusDays(1), zone)
        val hits = traceBarHits(
            supportingTimestampsMs = listOf(inWindow, tooOld, tooNew),
            asOfMs = asOf,
            zone = zone,
        )
        // today - 7 = index 29 - 7 = 22
        assertEquals(setOf(22), hits)
    }

    @Test
    fun `empty entries produce an empty hit set`() {
        val asOf = localDateToEpochMillis(LocalDate.parse("2026-05-12"), zone)
        assertTrue(traceBarHits(emptyList(), asOf, zone = zone).isEmpty())
    }

    @Test
    fun `non-default window size shifts the oldest boundary`() {
        val today = LocalDate.parse("2026-05-12")
        val sevenAgo = today.minusDays(6) // index 0 in a 7-day window
        val hits = traceBarHits(
            supportingTimestampsMs = listOf(localDateToEpochMillis(sevenAgo, zone)),
            asOfMs = localDateToEpochMillis(today, zone),
            days = 7,
            zone = zone,
        )
        assertEquals(setOf(0), hits)
    }

    @Test
    fun `non-positive window size is rejected`() {
        val asOf = localDateToEpochMillis(LocalDate.parse("2026-05-12"), zone)
        assertThrows(IllegalArgumentException::class.java) {
            traceBarHits(listOf(asOf), asOf, days = 0, zone = zone)
        }
        assertThrows(IllegalArgumentException::class.java) {
            traceBarHits(listOf(asOf), asOf, days = -3, zone = zone)
        }
    }

    @Test
    fun `cross-zone boundary lands on the local-zone day`() {
        // 23:30 UTC on the 11th = 08:30 JST on the 12th. In Asia/Tokyo (UTC+9), this is "today".
        val asOf = localDateToEpochMillis(LocalDate.parse("2026-05-12"), ZoneOffset.ofHours(9))
        val borderline = java.time.Instant.parse("2026-05-11T23:30:00Z").toEpochMilli()
        val hits = traceBarHits(listOf(borderline), asOf, zone = ZoneOffset.ofHours(9))
        assertEquals(setOf(29), hits)
    }

    // endregion

    // region helpers

    @Test
    fun `isHit reports membership of a given index`() {
        val hits = setOf(3, 17, 29)
        assertTrue(isHit(hits, 17))
        assertFalse(isHit(hits, 18))
    }

    @Test
    fun `hasRecentHits flips on the presence of any hit`() {
        assertFalse(hasRecentHits(emptySet()))
        assertTrue(hasRecentHits(setOf(0)))
    }

    // endregion
}
