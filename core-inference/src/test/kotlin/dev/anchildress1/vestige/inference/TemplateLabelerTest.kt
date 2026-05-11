package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.ConfidenceVerdict
import dev.anchildress1.vestige.model.ResolvedExtraction
import dev.anchildress1.vestige.model.ResolvedField
import dev.anchildress1.vestige.model.TemplateLabel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class TemplateLabelerTest {

    private val zone: ZoneId = ZoneId.of("America/Chicago")
    private val noon: ZonedDateTime = LocalDateTime.of(2026, 5, 9, 12, 0).atZone(zone)
    private val threeAm: ZonedDateTime = LocalDateTime.of(2026, 5, 9, 3, 0).atZone(zone)
    private val fiveAm: ZonedDateTime = LocalDateTime.of(2026, 5, 9, 5, 0).atZone(zone)

    private val labeler = TemplateLabeler()

    @Test
    fun `crashed plus state shift resolves to aftermath`() {
        val resolved = resolved(
            "energy_descriptor" to "crashed".canonical(),
            "state_shift" to true.canonical(),
        )

        assertEquals(TemplateLabel.AFTERMATH, labeler.label(resolved, capturedAt = noon))
    }

    @Test
    fun `crashed without state shift falls through to audit`() {
        val resolved = resolved(
            "energy_descriptor" to "crashed".canonical(),
            "state_shift" to false.canonical(),
        )

        assertEquals(TemplateLabel.AUDIT, labeler.label(resolved, capturedAt = noon))
    }

    @Test
    fun `aftermath energy match is case-insensitive and trimmed`() {
        val resolved = resolved(
            "energy_descriptor" to "  CRASHED  ".canonical(),
            "state_shift" to true.canonical(),
        )

        assertEquals(TemplateLabel.AFTERMATH, labeler.label(resolved, capturedAt = noon))
    }

    @Test
    fun `tunnel-exit tag yields tunnel exit label`() {
        val resolved = resolved("tags" to listOf("standup", "tunnel-exit").canonical())

        assertEquals(TemplateLabel.TUNNEL_EXIT, labeler.label(resolved, capturedAt = noon))
    }

    @Test
    fun `decision-loop tag yields decision spiral label`() {
        val resolved = resolved("tags" to listOf("decision-loop").canonical())

        assertEquals(TemplateLabel.DECISION_SPIRAL, labeler.label(resolved, capturedAt = noon))
    }

    @Test
    fun `stuck marker tag yields concrete shoes label`() {
        val resolved = resolved("tags" to listOf("stuck", "q3-doc").canonical())

        assertEquals(TemplateLabel.CONCRETE_SHOES, labeler.label(resolved, capturedAt = noon))
    }

    @Test
    fun `late-night tag inside the midnight-5am window yields goblin hours`() {
        val resolved = resolved("tags" to listOf("late-night").canonical())

        assertEquals(TemplateLabel.GOBLIN_HOURS, labeler.label(resolved, capturedAt = threeAm))
    }

    @Test
    fun `late-night tag outside the window falls through to audit`() {
        val resolved = resolved("tags" to listOf("late-night").canonical())

        assertEquals(TemplateLabel.AUDIT, labeler.label(resolved, capturedAt = noon))
    }

    @Test
    fun `5am is past the goblin window so late-night at 5am does not label as goblin hours`() {
        val resolved = resolved("tags" to listOf("late-night").canonical())

        assertEquals(TemplateLabel.AUDIT, labeler.label(resolved, capturedAt = fiveAm))
    }

    @Test
    fun `goblin window without late-night tag still falls through to audit`() {
        val resolved = resolved("tags" to listOf("standup").canonical())

        assertEquals(TemplateLabel.AUDIT, labeler.label(resolved, capturedAt = threeAm))
    }

    @Test
    fun `empty resolved extraction labels as audit`() {
        assertEquals(TemplateLabel.AUDIT, labeler.label(ResolvedExtraction(emptyMap()), capturedAt = noon))
    }

    @Test
    fun `aftermath wins over tunnel exit when both signals fire`() {
        val resolved = resolved(
            "energy_descriptor" to "crashed".canonical(),
            "state_shift" to true.canonical(),
            "tags" to listOf("tunnel-exit").canonical(),
        )

        assertEquals(TemplateLabel.AFTERMATH, labeler.label(resolved, capturedAt = threeAm))
    }

    @Test
    fun `aftermath wins over goblin hours at 3am`() {
        val resolved = resolved(
            "energy_descriptor" to "crashed".canonical(),
            "state_shift" to true.canonical(),
            "tags" to listOf("late-night").canonical(),
        )

        assertEquals(TemplateLabel.AFTERMATH, labeler.label(resolved, capturedAt = threeAm))
    }

    @Test
    fun `decision spiral wins over tunnel exit when both tags are present`() {
        val resolved = resolved("tags" to listOf("tunnel-exit", "decision-loop").canonical())

        assertEquals(TemplateLabel.DECISION_SPIRAL, labeler.label(resolved, capturedAt = noon))
    }

    @Test
    fun `concrete shoes wins over goblin hours when both signals fire at 3am`() {
        val resolved = resolved("tags" to listOf("stuck", "late-night").canonical())

        assertEquals(TemplateLabel.CONCRETE_SHOES, labeler.label(resolved, capturedAt = threeAm))
    }

    @Test
    fun `ambiguous tags field with null value labels as audit`() {
        val resolved = ResolvedExtraction(
            mapOf("tags" to ResolvedField(value = null, verdict = ConfidenceVerdict.AMBIGUOUS)),
        )

        assertEquals(TemplateLabel.AUDIT, labeler.label(resolved, capturedAt = noon))
    }

    @Test
    fun `tag matching is case-insensitive`() {
        val resolved = resolved("tags" to listOf("Decision-Loop").canonical())

        assertEquals(TemplateLabel.DECISION_SPIRAL, labeler.label(resolved, capturedAt = noon))
    }

    @Test
    fun `candidate verdict on energy descriptor is not load-bearing for aftermath`() {
        val resolved = ResolvedExtraction(
            mapOf(
                "energy_descriptor" to ResolvedField(
                    value = "crashed",
                    verdict = ConfidenceVerdict.CANDIDATE,
                    sourceLens = dev.anchildress1.vestige.model.Lens.INFERENTIAL,
                ),
                "state_shift" to true.canonical(),
            ),
        )

        assertEquals(TemplateLabel.AUDIT, labeler.label(resolved, capturedAt = noon))
    }

    @Test
    fun `candidate tags do not trigger archetype labels`() {
        val resolved = ResolvedExtraction(
            mapOf(
                "tags" to ResolvedField(
                    value = listOf("tunnel-exit"),
                    verdict = ConfidenceVerdict.CANDIDATE,
                    sourceLens = dev.anchildress1.vestige.model.Lens.LITERAL,
                ),
            ),
        )

        assertEquals(TemplateLabel.AUDIT, labeler.label(resolved, capturedAt = noon))
    }

    @Test
    fun `canonical-with-conflict still drives the label`() {
        val resolved = ResolvedExtraction(
            mapOf(
                "energy_descriptor" to ResolvedField(
                    value = "crashed",
                    verdict = ConfidenceVerdict.CANONICAL_WITH_CONFLICT,
                    flags = listOf("vocabulary-contradiction:fine vs couldn't"),
                ),
                "state_shift" to true.canonical(),
            ),
        )

        assertEquals(TemplateLabel.AFTERMATH, labeler.label(resolved, capturedAt = noon))
    }

    @Test
    fun `midnight local time is inside the goblin window`() {
        val midnight = LocalDateTime.of(2026, 5, 9, 0, 0).atZone(zone)
        val resolved = resolved("tags" to listOf("late-night").canonical())

        assertEquals(TemplateLabel.GOBLIN_HOURS, labeler.label(resolved, capturedAt = midnight))
    }

    @Test
    fun `0459 local time is the inclusive upper edge of the goblin window`() {
        val justBeforeFive = LocalDateTime.of(2026, 5, 9, 4, 59).atZone(zone)
        val resolved = resolved("tags" to listOf("late-night").canonical())

        assertEquals(TemplateLabel.GOBLIN_HOURS, labeler.label(resolved, capturedAt = justBeforeFive))
    }

    @Test
    fun `capture zone drives the goblin window — same UTC instant under different zones flips the label`() {
        // 08:00 UTC = 03:00 America/Chicago (inside) vs 08:00 UTC (outside). The labeler reads
        // hour from the captured ZonedDateTime, not from any ambient JVM default.
        val instant = Instant.parse("2026-05-09T08:00:00Z")
        val resolved = resolved("tags" to listOf("late-night").canonical())

        val labeledLocal = labeler.label(resolved, capturedAt = instant.atZone(ZoneId.of("America/Chicago")))
        val labeledUtc = labeler.label(resolved, capturedAt = instant.atZone(ZoneId.of("UTC")))

        assertEquals(TemplateLabel.GOBLIN_HOURS, labeledLocal)
        assertEquals(TemplateLabel.AUDIT, labeledUtc)
    }

    @Test
    fun `non-string tags entries are skipped without throwing`() {
        // The lens output coerces tags to strings, but a downstream miscast must not crash the
        // labeler — defensive against future schema drift.
        val resolved = ResolvedExtraction(
            mapOf(
                "tags" to ResolvedField(
                    value = listOf("stuck", 42, null),
                    verdict = ConfidenceVerdict.CANONICAL,
                ),
            ),
        )

        assertEquals(TemplateLabel.CONCRETE_SHOES, labeler.label(resolved, capturedAt = noon))
    }

    private fun resolved(vararg entries: Pair<String, ResolvedField>): ResolvedExtraction =
        ResolvedExtraction(mapOf(*entries))

    private fun Any?.canonical(): ResolvedField = ResolvedField(value = this, verdict = ConfidenceVerdict.CANONICAL)
}
