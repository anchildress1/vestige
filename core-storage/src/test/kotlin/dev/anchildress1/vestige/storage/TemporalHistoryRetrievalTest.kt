package dev.anchildress1.vestige.storage

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TemporalHistoryRetrievalTest {

    // America/New_York is UTC-4 in spring; afternoon block = local 12:00-16:59.
    private val zone = ZoneId.of("America/New_York")

    private fun entry(id: Long, instant: String): EntryEntity = EntryEntity(
        markdownFilename = "e$id.md",
        entryText = "entry $id",
        timestampEpochMs = Instant.parse(instant).toEpochMilli(),
    ).also { it.id = id }

    @Test
    fun `returns prior same-weekday same-time-block entries, most recent first`() {
        val target = entry(3, "2026-05-20T18:00:00Z") // Wed 2:00pm EDT
        val candidates = listOf(
            entry(1, "2026-05-06T17:30:00Z"), // Wed 1:30pm EDT — match
            entry(2, "2026-05-13T19:00:00Z"), // Wed 3:00pm EDT — match
            entry(4, "2026-05-19T18:00:00Z"), // Tue 2:00pm EDT — wrong weekday
            entry(5, "2026-05-13T13:00:00Z"), // Wed 9:00am EDT — wrong time block
            entry(6, "2026-05-27T18:00:00Z"), // Wed afternoon but after target — excluded
            target, // self — excluded
        )

        val result = TemporalHistoryRetrieval.matching(target, candidates, zone, limit = 5)

        assertEquals(listOf(2L, 1L), result.map { it.id })
    }

    @Test
    fun `caps results at the limit, newest first`() {
        val target = entry(99, "2026-05-20T18:00:00Z")
        val priors = listOf(
            entry(1, "2026-04-29T18:00:00Z"), // Wed afternoon
            entry(2, "2026-05-06T18:00:00Z"), // Wed afternoon
            entry(3, "2026-05-13T18:00:00Z"), // Wed afternoon
        )

        val result = TemporalHistoryRetrieval.matching(target, priors, zone, limit = 2)

        assertEquals(listOf(3L, 2L), result.map { it.id })
    }
}
