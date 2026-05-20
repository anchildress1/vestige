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
        assertEquals("3 Aftermath entries share the same resolved shape.", text)
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
        assertEquals("3 entries landed between midnight and 5am.", text)
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
        assertEquals("\"Tired\" spans 4 entries with multiple framings.", text)
    }

    @Test
    fun `multi-word kebab labels humanize cleanly`() {
        val text = PatternCalloutText.build(
            detected(PatternKind.TEMPLATE_RECURRENCE, "{\"label\":\"tunnel-exit\"}", templateLabel = "tunnel-exit"),
        )
        assertTrue(text.contains("Tunnel Exit"))
    }

    @Test
    fun `template recurrence falls back cleanly when signature json is malformed`() {
        val text = PatternCalloutText.build(detected(PatternKind.TEMPLATE_RECURRENCE, "{bad-json"))
        assertEquals("3 entries share the same resolved shape.", text)
    }

    @Test
    fun `tag pair callout falls back cleanly when tags array is missing`() {
        val text = PatternCalloutText.build(
            detected(PatternKind.TAG_PAIR_CO_OCCURRENCE, "{\"label\":\"aftermath\"}"),
        )
        assertEquals("3 entries share a tag pair.", text)
    }

    @Test
    fun `tag pair callout falls back cleanly when label is blank`() {
        val text = PatternCalloutText.build(
            detected(PatternKind.TAG_PAIR_CO_OCCURRENCE, "{\"tags\":[\"crashed\",\"standup\"]}"),
        )
        assertEquals("3 entries share a tag pair.", text)
    }

    @Test
    fun `commitment callout falls back cleanly when topic is blank`() {
        val text = PatternCalloutText.build(detected(PatternKind.COMMITMENT_RECURRENCE, "{}"))
        assertEquals("3 entries reference the same commitment.", text)
    }

    @Test
    fun `vocab callout falls back cleanly when token is blank`() {
        val text = PatternCalloutText.build(
            detected(PatternKind.VOCAB_FREQUENCY, "{}", supporting = listOf(1L, 2L, 3L, 4L)),
        )
        assertEquals("4 entries share a vocab token.", text)
    }

    @Test
    fun `temporal weekday callout names the calendar slot`() {
        val text = PatternCalloutText.build(
            detected(
                PatternKind.TEMPORAL_RELATIVE,
                "{\"relation\":\"weekday_time_block\",\"day_of_week\":\"tuesday\",\"time_block\":\"afternoon\"}",
            ),
        )

        assertEquals("3 Tuesday afternoon entries logged. Same slot keeps showing up.", text)
    }

    @Test
    fun `temporal month-start callout names the calendar edge`() {
        val text = PatternCalloutText.build(
            detected(PatternKind.TEMPORAL_RELATIVE, "{\"relation\":\"month_start\",\"day_of_month\":1}"),
        )

        assertEquals("3 first-of-month entries logged. Same calendar edge keeps showing up.", text)
    }

    @Test
    fun `temporal with unknown relation falls back to generic calendar-slot copy`() {
        val text = PatternCalloutText.build(
            detected(PatternKind.TEMPORAL_RELATIVE, "{\"relation\":\"unknown_future_kind\"}"),
        )

        assertEquals("3 time-relative entries logged. Same calendar slot keeps showing up.", text)
    }
}
