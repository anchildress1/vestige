package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.Lens
import dev.anchildress1.vestige.model.LensExtraction
import dev.anchildress1.vestige.model.TemplateLabel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class BackgroundExtractionWorkerTest {

    private fun compactSuccessJson(stateShift: Boolean): String = """
        {"tags":["sink"],"energy_descriptor":null,"state_shift":$stateShift,"vocabulary_contradictions":[],"stated_commitment":null,"recurrence_link":null,"recurrence_kind":null,"flags":[]}
    """.trimIndent()

    private fun malformedSkepticalJson(): String = """
        {
        "tags": ["sink", "noon", "1pm", "three-hours-later"],
        "energy_descriptor": null,
        "state_shift": true
        "vocabulary_contradictions": [
        {
        "term_a": "fine",
        "term_b": "not tired exactly",
        "snippet": "completely fine by 1pm i was gone not tired exactly"
        }
        ]
        "stated_commitment": null
        "recurrence_link": null
        "recurrence_kind": null
        "flags": [
        {
        "kind": "vocabulary-contradiction",
        "snippet": "completely fine by 1pm i was gone not tired exactly",
        "note": "The user describes a state of being fine then immediately negates it with 'not tired exactly'."
        }
        ]
        }
    """.trimIndent()

    private fun skepticalFlag(): String =
        "vocabulary-contradiction:completely fine by 1pm i was gone not tired exactly:" +
            "The user describes a state of being fine then immediately negates it with 'not tired exactly'."

    @Test
    fun `runs three lenses sequentially and resolves on first-attempt success`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText("prompt-for-LITERAL", any()) } returns flowOf("raw-literal")
        every { engine.streamText("prompt-for-INFERENTIAL", any()) } returns flowOf("raw-inferential")
        every { engine.streamText("prompt-for-SKEPTICAL", any()) } returns flowOf("raw-skeptical")
        val resolver = RecordingResolver(resolved)
        val seenRaws = mutableMapOf<Lens, String>()
        val parser: (Lens, String) -> LensExtraction? = { lens, raw ->
            seenRaws[lens] = raw
            extraction(lens)
        }
        val listener = RecordingListener()

        val worker = BackgroundExtractionWorker(
            engine = engine,
            resolver = resolver,
            parser = parser,
            composer = fakeComposer(),
        )

        val result = worker.extract(
            request = BackgroundExtractionRequest(entryText = "the user said something", capturedAt = capturedAt),
            listener = listener,
        )

        val success = assertInstanceOf(BackgroundExtractionResult.Success::class.java, result)
        assertAll(
            { assertSame(resolved, success.resolved) },
            { assertEquals(TemplateLabel.AFTERMATH, success.templateLabel) },
            { assertEquals(3, success.lensResults.size) },
            { assertEquals(3, success.modelCallCount) },
            {
                assertEquals(
                    listOf(Lens.LITERAL, Lens.INFERENTIAL, Lens.SKEPTICAL),
                    success.lensResults.map {
                        it.lens
                    },
                )
            },
            { assertEquals(3, resolver.captured.size) },
            {
                assertEquals(
                    mapOf(
                        Lens.LITERAL to "raw-literal",
                        Lens.INFERENTIAL to "raw-inferential",
                        Lens.SKEPTICAL to "raw-skeptical",
                    ),
                    seenRaws,
                )
            },
        )
        // Listener fires exactly twice on the happy path: initial RUNNING and terminal COMPLETED.
        // No retry events since every lens parsed cleanly on attempt 1.
        assertEquals(
            listOf(
                RecordingListener.Update(ExtractionStatus.RUNNING, 0, null),
                RecordingListener.Update(ExtractionStatus.COMPLETED, 0, null),
            ),
            listener.updates,
        )
    }

    @Test
    fun `single inferential lens config runs one model call and resolves it`() = runTest {
        // Tuning-harness config: one Inferential pass, no cross-lens convergence.
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText("prompt-for-INFERENTIAL", any()) } returns flowOf("raw-inferential")
        val resolver = RecordingResolver(resolved)
        val listener = RecordingListener()

        val result = BackgroundExtractionWorker(
            engine = engine,
            resolver = resolver,
            parser = { lens, _ -> extraction(lens) },
            composer = fakeComposer(),
            lenses = listOf(Lens.INFERENTIAL),
        ).extract(request = request, listener = listener)

        val success = assertInstanceOf(BackgroundExtractionResult.Success::class.java, result)
        assertAll(
            { assertEquals(1, success.lensResults.size) },
            { assertEquals(1, success.modelCallCount) },
            { assertEquals(listOf(Lens.INFERENTIAL), success.lensResults.map { it.lens }) },
            { assertEquals(1, resolver.captured.size) },
            { assertEquals(Lens.INFERENTIAL, resolver.captured.single().lens) },
        )
        assertEquals(
            listOf(
                RecordingListener.Update(ExtractionStatus.RUNNING, 0, null),
                RecordingListener.Update(ExtractionStatus.COMPLETED, 0, null),
            ),
            listener.updates,
        )
    }

    @Test
    fun `lenses run sequentially, never concurrently — SDK is single-session`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        val inFlight = AtomicInteger(0)
        var maxInFlight = 0
        val callOrder = mutableListOf<String>()
        every { engine.streamText(any(), any()) } answers {
            flow {
                callOrder += firstArg<String>()
                inFlight.incrementAndGet().also { if (it > maxInFlight) maxInFlight = it }
                delay(10)
                inFlight.decrementAndGet()
                emit("raw")
            }
        }

        BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(resolved),
            parser = { lens, _ -> extraction(lens) },
            composer = fakeComposer(),
        ).extract(
            request = BackgroundExtractionRequest(entryText = "user words", capturedAt = capturedAt),
        )

        assertAll(
            { assertEquals(1, maxInFlight, "single-session SDK: at most one lens call in-flight at a time") },
            {
                assertEquals(
                    listOf("prompt-for-LITERAL", "prompt-for-INFERENTIAL", "prompt-for-SKEPTICAL"),
                    callOrder,
                    "lenses must run one at a time in LENSES order",
                )
            },
        )
    }

    @Test
    fun `worker parses malformed skeptical near-json without burning retries`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText("prompt-for-LITERAL", any()) } returns flowOf(compactSuccessJson(stateShift = true))
        every { engine.streamText("prompt-for-INFERENTIAL", any()) } returns
            flowOf(compactSuccessJson(stateShift = false))
        every { engine.streamText("prompt-for-SKEPTICAL", any()) } returns flowOf(malformedSkepticalJson())
        val listener = RecordingListener()

        val result = BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(resolved),
            composer = fakeComposer(),
        ).extract(request = request, listener = listener)

        val success = assertInstanceOf(BackgroundExtractionResult.Success::class.java, result)
        val skepticalResult = success.lensResults.first { it.lens == Lens.SKEPTICAL }
        assertAll(
            { assertNotNull(skepticalResult.extraction) },
            { assertEquals(1, skepticalResult.attemptCount) },
            { assertNull(skepticalResult.lastError) },
            {
                assertEquals(
                    listOf(skepticalFlag()),
                    skepticalResult.extraction!!.flags,
                )
            },
            { assertEquals(3, success.modelCallCount) },
        )
        assertEquals(
            listOf(
                RecordingListener.Update(ExtractionStatus.RUNNING, 0, null),
                RecordingListener.Update(ExtractionStatus.COMPLETED, 0, null),
            ),
            listener.updates,
        )
    }

    @Test
    fun `retries a lens once on parse failure and counts both attempts`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText("prompt-for-LITERAL", any()) } returnsMany
            listOf(flowOf("garbage-1"), flowOf("raw-literal"))
        every { engine.streamText("prompt-for-INFERENTIAL", any()) } returns flowOf("raw-inferential")
        every { engine.streamText("prompt-for-SKEPTICAL", any()) } returns flowOf("raw-skeptical")
        val parser: (Lens, String) -> LensExtraction? = { lens, raw ->
            if (raw == "garbage-1") null else extraction(lens)
        }
        val listener = RecordingListener()

        val worker = BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(resolved),
            parser = parser,
            composer = fakeComposer(),
        )

        val result = worker.extract(request = request, listener = listener)

        val success = assertInstanceOf(BackgroundExtractionResult.Success::class.java, result)
        assertEquals(4, success.modelCallCount, "1 retry on LITERAL + 1 each on INFERENTIAL/SKEPTICAL = 4")
        assertEquals(2, success.lensResults.first { it.lens == Lens.LITERAL }.attemptCount)
        // Single RUNNING for the whole worker: per-lens retries no longer emit their own status.
        // The retry is still observable on the lens result's attemptCount above.
        assertEquals(
            listOf(
                RecordingListener.Update(ExtractionStatus.RUNNING, 0, null),
                RecordingListener.Update(ExtractionStatus.COMPLETED, 0, null),
            ),
            listener.updates,
        )
    }

    @Test
    fun `lens that exhausts retry budget contributes null extraction and convergence still runs`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText("prompt-for-LITERAL", any()) } returns flowOf("raw-literal")
        every { engine.streamText("prompt-for-INFERENTIAL", any()) } returnsMany
            listOf(flowOf("garbage-1"), flowOf("garbage-2"))
        every { engine.streamText("prompt-for-SKEPTICAL", any()) } returns flowOf("raw-skeptical")
        val parser: (Lens, String) -> LensExtraction? = { lens, raw ->
            if (raw.startsWith("garbage")) null else extraction(lens)
        }
        val resolver = RecordingResolver(resolved)
        val listener = RecordingListener()

        val result = BackgroundExtractionWorker(
            engine = engine,
            resolver = resolver,
            parser = parser,
            composer = fakeComposer(),
        ).extract(request = request, listener = listener)

        val success = assertInstanceOf(BackgroundExtractionResult.Success::class.java, result)
        assertEquals(
            2,
            resolver.captured.size,
            "INFERENTIAL exhausted its budget; LITERAL + SKEPTICAL feed convergence",
        )
        val inferentialResult = success.lensResults.first { it.lens == Lens.INFERENTIAL }
        assertAll(
            { assertNull(inferentialResult.extraction) },
            { assertEquals(2, inferentialResult.attemptCount) },
            { assertEquals("parse-fail", inferentialResult.lastError) },
        )
        // 1 (LITERAL ok) + 2 (INFERENTIAL exhausted) + 1 (SKEPTICAL ok) = 4
        assertEquals(4, success.modelCallCount)
    }

    @Test
    fun `every lens failing causes Failed result without invoking the resolver`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText(any(), any()) } returns flowOf("garbage-always")
        val resolver = RecordingResolver(resolved)
        val parser: (Lens, String) -> LensExtraction? = { _, _ -> null }
        val listener = RecordingListener()

        val result = BackgroundExtractionWorker(
            engine = engine,
            resolver = resolver,
            parser = parser,
            composer = fakeComposer(),
        ).extract(request = request, listener = listener)

        val failed = assertInstanceOf(BackgroundExtractionResult.Failed::class.java, result)
        assertAll(
            { assertEquals(6, failed.modelCallCount, "3 lenses × 2 attempts each = 6") },
            { assertEquals(3, failed.lensResults.size) },
            { assertTrue(failed.lensResults.all { it.extraction == null }) },
            { assertEquals("parse-fail", failed.lastError) },
            {
                assertEquals(
                    emptyList<LensExtraction>(),
                    resolver.captured,
                    "Resolver must not be invoked when all lenses fail",
                )
            },
        )
        assertEquals(ExtractionStatus.FAILED, listener.updates.last().status)
        assertEquals(0, listener.updates.last().entryAttemptCount)
    }

    @Test
    fun `engine error on a lens is treated as a parse failure for retry accounting`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText("prompt-for-LITERAL", any()) } throws IllegalStateException("OOM-like")
        every { engine.streamText("prompt-for-INFERENTIAL", any()) } returns flowOf("raw-inferential")
        every { engine.streamText("prompt-for-SKEPTICAL", any()) } returns flowOf("raw-skeptical")
        val parser: (Lens, String) -> LensExtraction? = { lens, raw ->
            if (raw.isEmpty()) null else extraction(lens)
        }
        val listener = RecordingListener()

        val result = BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(resolved),
            parser = parser,
            composer = fakeComposer(),
        ).extract(request = request, listener = listener)

        val success = assertInstanceOf(BackgroundExtractionResult.Success::class.java, result)
        val literalResult = success.lensResults.first { it.lens == Lens.LITERAL }
        assertAll(
            { assertNull(literalResult.extraction) },
            { assertEquals(2, literalResult.attemptCount) },
            { assertNotNull(literalResult.lastError) },
            { assertTrue(literalResult.lastError!!.startsWith("engine-error:")) },
        )
        val terminal = listener.updates.last()
        assertEquals(ExtractionStatus.COMPLETED, terminal.status)
        assertNull(terminal.lastError)
    }
}
