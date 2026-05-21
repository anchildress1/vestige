package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.ConfidenceVerdict
import dev.anchildress1.vestige.model.Lens
import dev.anchildress1.vestige.model.LensExtraction
import dev.anchildress1.vestige.model.ResolvedExtraction
import dev.anchildress1.vestige.model.ResolvedField
import dev.anchildress1.vestige.model.TemplateLabel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

class BackgroundExtractionWorkerLabelingTest {

    @Test
    fun `model-emitted template_label wins over the deterministic labeler`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText(any(), any()) } returns flowOf("raw-ok")
        // Energy "crashed" + state_shift true would make the labeler pick AFTERMATH; the model's
        // converged template_label overrides it.
        val modelLabeled = ResolvedExtraction(
            fields = mapOf(
                "energy_descriptor" to ResolvedField("crashed", ConfidenceVerdict.CANONICAL),
                "state_shift" to ResolvedField(true, ConfidenceVerdict.CANONICAL),
                "template_label" to ResolvedField("decision-spiral", ConfidenceVerdict.CANONICAL),
            ),
        )

        val result = BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(modelLabeled),
            parser = { lens, _ -> extraction(lens) },
            composer = fakeComposer(),
        ).extract(request = request)

        val success = assertInstanceOf(BackgroundExtractionResult.Success::class.java, result)
        assertEquals(TemplateLabel.DECISION_SPIRAL, success.templateLabel)
    }

    @Test
    fun `template_label falls back to the labeler when the model pick is absent`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText(any(), any()) } returns flowOf("raw-ok")
        // No template_label resolved -> labeler computes it (energy "crashed" + shift -> AFTERMATH).
        val result = BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(resolved),
            parser = { lens, _ -> extraction(lens) },
            composer = fakeComposer(),
        ).extract(request = request)

        val success = assertInstanceOf(BackgroundExtractionResult.Success::class.java, result)
        assertEquals(TemplateLabel.AFTERMATH, success.templateLabel)
    }

    @Test
    fun `unknown template_label serial falls back to the labeler`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText(any(), any()) } returns flowOf("raw-ok")
        val badLabel = ResolvedExtraction(
            fields = mapOf(
                "energy_descriptor" to ResolvedField("crashed", ConfidenceVerdict.CANONICAL),
                "state_shift" to ResolvedField(true, ConfidenceVerdict.CANONICAL),
                "template_label" to ResolvedField("not-a-real-archetype", ConfidenceVerdict.CANONICAL),
            ),
        )

        val result = BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(badLabel),
            parser = { lens, _ -> extraction(lens) },
            composer = fakeComposer(),
        ).extract(request = request)

        val success = assertInstanceOf(BackgroundExtractionResult.Success::class.java, result)
        assertEquals(TemplateLabel.AFTERMATH, success.templateLabel)
    }

    @Test
    fun `worker labels using the capture timestamp's zone, not the JVM default`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText(any(), any()) } returns flowOf("raw-ok")
        val lateNightResolved = ResolvedExtraction(
            fields = mapOf("tags" to ResolvedField(listOf("late-night"), ConfidenceVerdict.CANONICAL)),
        )
        val parser: (Lens, String) -> LensExtraction? = { lens, _ -> extraction(lens) }
        // 08:00 UTC = 03:00 Chicago (inside goblin) but 08:00 UTC zone (outside goblin). Asserting
        // both reads of the same instant proves the labeler reads the captured zone, not ambient.
        val instant = Instant.parse("2026-05-09T08:00:00Z")

        val chicagoResult = BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(lateNightResolved),
            parser = parser,
            composer = fakeComposer(),
        ).extract(
            request = BackgroundExtractionRequest(
                entryText = "user words",
                capturedAt = instant.atZone(ZoneId.of("America/Chicago")),
            ),
        )

        val utcResult = BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(lateNightResolved),
            parser = parser,
            composer = fakeComposer(),
        ).extract(
            request = BackgroundExtractionRequest(
                entryText = "user words",
                capturedAt = instant.atZone(ZoneId.of("UTC")),
            ),
        )

        assertEquals(
            TemplateLabel.GOBLIN_HOURS,
            assertInstanceOf(BackgroundExtractionResult.Success::class.java, chicagoResult).templateLabel,
        )
        assertEquals(
            TemplateLabel.AUDIT,
            assertInstanceOf(BackgroundExtractionResult.Success::class.java, utcResult).templateLabel,
        )
    }
}
