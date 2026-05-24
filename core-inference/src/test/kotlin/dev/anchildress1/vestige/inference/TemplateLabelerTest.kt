package dev.anchildress1.vestige.inference

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The labeler is now a pure clock predicate: is the local capture hour inside the goblin window
 * (00:00–04:59)? Template *picking* is the model's job; this only answers the question the
 * `audit` → goblin override needs.
 */
class TemplateLabelerTest {

    private val zone: ZoneId = ZoneId.of("America/Chicago")
    private val labeler = TemplateLabeler()

    private fun at(hour: Int, minute: Int = 0): ZonedDateTime = LocalDateTime.of(2026, 5, 9, hour, minute).atZone(zone)

    @Test
    fun `noon is outside the goblin window`() {
        assertFalse(labeler.isGoblinHours(at(12)))
    }

    @Test
    fun `3am is inside the goblin window`() {
        assertTrue(labeler.isGoblinHours(at(3)))
    }

    @Test
    fun `midnight local time is the inclusive lower edge`() {
        assertTrue(labeler.isGoblinHours(at(0)))
    }

    @Test
    fun `0459 local time is the inclusive upper edge`() {
        assertTrue(labeler.isGoblinHours(at(4, 59)))
    }

    @Test
    fun `5am is past the goblin window`() {
        assertFalse(labeler.isGoblinHours(at(5)))
    }

    @Test
    fun `capture zone drives the window — same UTC instant under different zones flips the answer`() {
        // 08:00 UTC = 03:00 America/Chicago (hour 3, inside) vs 08:00 in UTC (hour 8, outside). The
        // predicate reads the hour from the captured ZonedDateTime, not from any ambient JVM default.
        val instant = Instant.parse("2026-05-09T08:00:00Z")

        assertTrue(labeler.isGoblinHours(instant.atZone(ZoneId.of("America/Chicago"))))
        assertFalse(labeler.isGoblinHours(instant.atZone(ZoneId.of("UTC"))))
    }
}
