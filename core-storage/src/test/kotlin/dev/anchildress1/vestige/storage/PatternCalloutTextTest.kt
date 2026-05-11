package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.DetectedPattern
import dev.anchildress1.vestige.model.PatternKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternCalloutTextTest {

    private fun detected(
        kind: PatternKind,
        signatureJson: String,
        supporting: List<Long> = listOf(1L, 2L, 3L),
        templateLabel: String? = null,
    ) = DetectedPattern(
        patternId = "x".repeat(64),
        kind = kind,
        signatureJson = signatureJson,
        templateLabel = templateLabel,
        supportingEntryIds = supporting,
        firstSeenTimestamp = 1L,
        lastSeenTimestamp = 2L,
    )

    @Test
    fun `template recurrence callout names the label and count`() {
        val text = PatternCalloutText.build(
            detected(PatternKind.TEMPLATE_RECURRENCE, "{\"label\":\"aftermath\"}", templateLabel = "aftermath"),
        )
        assertEquals("3 Aftermath entries logged. Worth noting.", text)
    }

    @Test
    fun `tag pair callout joins tags with plus separator`() {
        val text = PatternCalloutText.build(
            detected(
                PatternKind.TAG_PAIR_CO_OCCURRENCE,
                "{\"label\":\"aftermath\",\"tags\":[\"crashed\",\"standup\"]}",
                supporting = listOf(1L, 2L, 3L, 4L),
                templateLabel = "aftermath",
            ),
        )
        assertEquals("Aftermath entries: crashed + standup across 4 entries.", text)
    }

    @Test
    fun `goblin callout never mentions a label`() {
        val text = PatternCalloutText.build(detected(PatternKind.TIME_OF_DAY_CLUSTER, "{\"bucket\":\"goblin\"}"))
        assertEquals("3 entries between midnight and 5am. Same admin loop.", text)
    }

    @Test
    fun `commitment callout includes the topic`() {
        val text = PatternCalloutText.build(
            detected(PatternKind.COMMITMENT_RECURRENCE, "{\"topic_or_person\":\"jamie\"}"),
        )
        assertEquals("3 entries with a commitment about Jamie.", text)
    }

    @Test
    fun `vocab callout quotes the token`() {
        val text = PatternCalloutText.build(
            detected(PatternKind.VOCAB_FREQUENCY, "{\"token\":\"tired\"}", supporting = listOf(1L, 2L, 3L, 4L)),
        )
        assertEquals("'Tired' appears across 4 entries with multiple framings.", text)
    }

    @Test
    fun `multi-word kebab labels humanize cleanly`() {
        val text = PatternCalloutText.build(
            detected(PatternKind.TEMPLATE_RECURRENCE, "{\"label\":\"tunnel-exit\"}", templateLabel = "tunnel-exit"),
        )
        assertTrue(text.contains("Tunnel Exit"))
    }
}
