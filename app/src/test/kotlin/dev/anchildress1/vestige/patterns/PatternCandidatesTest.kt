package dev.anchildress1.vestige.patterns

import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.PatternState
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.ui.history.HistoryTestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = HistoryTestApplication::class)
class PatternCandidatesTest {

    private val zone = ZoneOffset.UTC
    private val tuesdayAfternoon =
        """{"relation":"weekday_time_block","day_of_week":"tuesday","time_block":"afternoon"}"""

    private fun entry(id: Long, instant: String, text: String = ""): EntryEntity =
        EntryEntity(entryText = text, timestampEpochMs = Instant.parse(instant).toEpochMilli()).also { it.id = id }

    private fun activePattern(priors: List<EntryEntity>): PatternEntity = PatternEntity(
        patternId = "p1",
        kind = PatternKind.TEMPORAL_RELATIVE,
        signatureJson = tuesdayAfternoon,
        title = "Tuesday Crash",
        state = PatternState.ACTIVE,
    ).also { p -> priors.forEach { p.supportingEntries.add(it) } }

    @Test
    fun `emits one chunk with the pattern id and prior entries most-recent first`() {
        val target = entry(4, "2026-05-26T14:00:00Z", "fourth")
        val pattern = activePattern(
            listOf(
                entry(1, "2026-05-05T14:00:00Z", "may five"),
                entry(2, "2026-05-12T14:00:00Z", "may twelve"),
                entry(3, "2026-05-19T14:00:00Z", "may nineteen"),
            ),
        )

        val chunks = PatternCandidates.forEntry(target, listOf(pattern), zone, maxPriorEntries = 3)

        assertEquals(1, chunks.size)
        assertEquals("p1", chunks.single().patternId)
        assertEquals("may nineteen\n\nmay twelve\n\nmay five", chunks.single().text)
    }

    @Test
    fun `caps prior entries at maxPriorEntries`() {
        val target = entry(4, "2026-05-26T14:00:00Z")
        val pattern = activePattern(
            listOf(
                entry(1, "2026-05-05T14:00:00Z", "may five"),
                entry(2, "2026-05-12T14:00:00Z", "may twelve"),
                entry(3, "2026-05-19T14:00:00Z", "may nineteen"),
            ),
        )

        assertEquals(
            "may nineteen\n\nmay twelve",
            PatternCandidates.forEntry(target, listOf(pattern), zone, 2).single().text,
        )
    }

    @Test
    fun `drops a matching pattern whose only supporter is not strictly earlier`() {
        val target = entry(4, "2026-05-26T14:00:00Z")
        val pattern = activePattern(listOf(entry(5, "2026-06-02T14:00:00Z", "later")))

        assertTrue(PatternCandidates.forEntry(target, listOf(pattern), zone, 3).isEmpty())
    }

    @Test
    fun `ignores a pattern the entry does not match`() {
        // 2026-05-25 is a Monday — different weekday slot than the Tuesday-afternoon signature.
        val target = entry(4, "2026-05-25T14:00:00Z")
        val pattern = activePattern(listOf(entry(1, "2026-05-12T14:00:00Z", "may twelve")))

        assertTrue(PatternCandidates.forEntry(target, listOf(pattern), zone, 3).isEmpty())
    }
}
