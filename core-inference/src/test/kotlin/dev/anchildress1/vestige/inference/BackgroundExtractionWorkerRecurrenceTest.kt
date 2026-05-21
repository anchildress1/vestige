package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.ConfidenceVerdict
import dev.anchildress1.vestige.model.ResolvedExtraction
import dev.anchildress1.vestige.model.ResolvedField
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class BackgroundExtractionWorkerRecurrenceTest {

    @Test
    fun `chunk reference in recurrence_link is resolved to actual pattern id after convergence`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText(any(), any()) } returns flowOf("raw")
        val history = listOf(HistoryChunk(patternId = "real-pattern-id-abc", text = "prior entry"))
        val resolvedWithChunkRef = ResolvedExtraction(
            fields = mapOf(
                "recurrence_link" to ResolvedField("chunk-1", ConfidenceVerdict.CANONICAL),
                "recurrence_kind" to ResolvedField("partial", ConfidenceVerdict.CANONICAL),
            ),
        )

        val result = BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(resolvedWithChunkRef),
            parser = { lens, _ -> extraction(lens) },
            composer = fakeComposer(),
        ).extract(
            request = BackgroundExtractionRequest(
                entryText = "user words",
                capturedAt = capturedAt,
                retrievedHistory = history,
            ),
        )

        val success = assertInstanceOf(BackgroundExtractionResult.Success::class.java, result)
        val link = success.resolved.fields["recurrence_link"]?.value as? String
        assertEquals("real-pattern-id-abc", link)
    }

    @Test
    fun `out-of-range chunk ref leaves recurrence_link as raw ref`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText(any(), any()) } returns flowOf("raw")
        val history = listOf(HistoryChunk(patternId = "only-entry", text = "prior entry"))
        val resolvedWithBadRef = ResolvedExtraction(
            fields = mapOf(
                "recurrence_link" to ResolvedField("chunk-9", ConfidenceVerdict.CANONICAL),
            ),
        )

        val result = BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(resolvedWithBadRef),
            parser = { lens, _ -> extraction(lens) },
            composer = fakeComposer(),
        ).extract(
            request = BackgroundExtractionRequest(
                entryText = "user words",
                capturedAt = capturedAt,
                retrievedHistory = history,
            ),
        )

        val success = assertInstanceOf(BackgroundExtractionResult.Success::class.java, result)
        val link = success.resolved.fields["recurrence_link"]?.value as? String
        assertEquals("chunk-9", link)
    }

    @Test
    fun `chunk ref pointing at context-only entry leaves recurrence_link as raw ref`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText(any(), any()) } returns flowOf("raw")
        val history = listOf(HistoryChunk(patternId = null, text = "context-only entry"))
        val resolvedWithChunkRef = ResolvedExtraction(
            fields = mapOf(
                "recurrence_link" to ResolvedField("chunk-1", ConfidenceVerdict.CANONICAL),
            ),
        )

        val result = BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(resolvedWithChunkRef),
            parser = { lens, _ -> extraction(lens) },
            composer = fakeComposer(),
        ).extract(
            request = BackgroundExtractionRequest(
                entryText = "user words",
                capturedAt = capturedAt,
                retrievedHistory = history,
            ),
        )

        val success = assertInstanceOf(BackgroundExtractionResult.Success::class.java, result)
        val link = success.resolved.fields["recurrence_link"]?.value as? String
        assertEquals("chunk-1", link)
    }

    @Test
    fun `non-chunk-ref value in recurrence_link passes through unchanged`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText(any(), any()) } returns flowOf("raw")
        val resolvedWithRealId = ResolvedExtraction(
            fields = mapOf(
                "recurrence_link" to ResolvedField("real-uuid-abc", ConfidenceVerdict.CANONICAL),
            ),
        )

        val result = BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(resolvedWithRealId),
            parser = { lens, _ -> extraction(lens) },
            composer = fakeComposer(),
        ).extract(
            request = BackgroundExtractionRequest(
                entryText = "user words",
                capturedAt = capturedAt,
                retrievedHistory = listOf(HistoryChunk(patternId = "some-id", text = "prior")),
            ),
        )

        val success = assertInstanceOf(BackgroundExtractionResult.Success::class.java, result)
        val link = success.resolved.fields["recurrence_link"]?.value as? String
        assertEquals("real-uuid-abc", link)
    }
}
