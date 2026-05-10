package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.ConfidenceVerdict
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.Lens
import dev.anchildress1.vestige.model.LensExtraction
import dev.anchildress1.vestige.model.ResolvedExtraction
import dev.anchildress1.vestige.model.ResolvedField
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackgroundExtractionWorkerTest {

    private val resolved = ResolvedExtraction(
        fields = mapOf("template_label" to ResolvedField("aftermath", ConfidenceVerdict.CANONICAL)),
    )

    private fun extraction(lens: Lens, label: String = "aftermath"): LensExtraction = LensExtraction(
        lens = lens,
        fields = mapOf("template_label" to label),
    )

    private fun fakeComposer(): (Lens, String, List<HistoryChunk>) -> ComposedPrompt = { lens, _, _ ->
        ComposedPrompt(lens = lens, text = "prompt-for-$lens", tokenEstimate = 100)
    }

    private class RecordingResolver(val resolved: ResolvedExtraction) : ConvergenceResolver {
        var captured: List<LensExtraction> = emptyList()
        override fun resolve(extractions: List<LensExtraction>): ResolvedExtraction {
            captured = extractions
            return resolved
        }
    }

    private class RecordingListener : ExtractionStatusListener {
        data class Update(val status: ExtractionStatus, val attemptCount: Int, val lastError: String?)
        val updates: MutableList<Update> = mutableListOf()
        override suspend fun onUpdate(status: ExtractionStatus, attemptCount: Int, lastError: String?) {
            updates += Update(status, attemptCount, lastError)
        }
    }

    @Test
    fun `runs three lenses sequentially and resolves on first-attempt success`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        coEvery { engine.generateText("prompt-for-LITERAL") } returns "raw-literal"
        coEvery { engine.generateText("prompt-for-INFERENTIAL") } returns "raw-inferential"
        coEvery { engine.generateText("prompt-for-SKEPTICAL") } returns "raw-skeptical"
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

        val result = worker.extract(entryText = "the user said something", listener = listener)

        val success = assertInstanceOf(BackgroundExtractionResult.Success::class.java, result)
        assertAll(
            { assertSame(resolved, success.resolved) },
            { assertEquals(3, success.lensResults.size) },
            { assertEquals(3, success.attemptCount) },
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
                RecordingListener.Update(ExtractionStatus.COMPLETED, 3, null),
            ),
            listener.updates,
        )
    }

    @Test
    fun `retries a lens once on parse failure and counts both attempts`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        coEvery { engine.generateText("prompt-for-LITERAL") } returnsMany listOf("garbage-1", "raw-literal")
        coEvery { engine.generateText("prompt-for-INFERENTIAL") } returns "raw-inferential"
        coEvery { engine.generateText("prompt-for-SKEPTICAL") } returns "raw-skeptical"
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

        val result = worker.extract(entryText = "user words", listener = listener)

        val success = assertInstanceOf(BackgroundExtractionResult.Success::class.java, result)
        assertEquals(4, success.attemptCount, "1 retry on LITERAL + 1 each on INFERENTIAL/SKEPTICAL = 4")
        assertEquals(2, success.lensResults.first { it.lens == Lens.LITERAL }.attemptCount)
        // Listener: initial RUNNING(0,null) → retry RUNNING(1,parse-fail) → terminal COMPLETED(4,parse-fail).
        // The terminal `lastError` carries the last failure seen across the run; convergence still
        // succeeded because the retry produced parseable output, so the entry's last_error
        // captures the transient failure for the debug surface (ADR-001 §Q3 last_error column).
        assertEquals(
            listOf(
                RecordingListener.Update(ExtractionStatus.RUNNING, 0, null),
                RecordingListener.Update(ExtractionStatus.RUNNING, 1, "parse-fail"),
                RecordingListener.Update(ExtractionStatus.COMPLETED, 4, "parse-fail"),
            ),
            listener.updates,
        )
    }

    @Test
    fun `lens that exhausts retry budget contributes null extraction and convergence still runs`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        coEvery { engine.generateText("prompt-for-LITERAL") } returns "raw-literal"
        coEvery { engine.generateText("prompt-for-INFERENTIAL") } returnsMany listOf("garbage-1", "garbage-2")
        coEvery { engine.generateText("prompt-for-SKEPTICAL") } returns "raw-skeptical"
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
        ).extract(entryText = "user words", listener = listener)

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
        assertEquals(4, success.attemptCount)
    }

    @Test
    fun `every lens failing causes Failed result without invoking the resolver`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        coEvery { engine.generateText(any()) } returns "garbage-always"
        val resolver = RecordingResolver(resolved)
        val parser: (Lens, String) -> LensExtraction? = { _, _ -> null }
        val listener = RecordingListener()

        val result = BackgroundExtractionWorker(
            engine = engine,
            resolver = resolver,
            parser = parser,
            composer = fakeComposer(),
        ).extract(entryText = "user words", listener = listener)

        val failed = assertInstanceOf(BackgroundExtractionResult.Failed::class.java, result)
        assertAll(
            { assertEquals(6, failed.attemptCount, "3 lenses × 2 attempts each = 6") },
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
        assertEquals(6, listener.updates.last().attemptCount)
    }

    @Test
    fun `engine error on a lens is treated as a parse failure for retry accounting`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        coEvery { engine.generateText("prompt-for-LITERAL") } throws IllegalStateException("OOM-like")
        coEvery { engine.generateText("prompt-for-INFERENTIAL") } returns "raw-inferential"
        coEvery { engine.generateText("prompt-for-SKEPTICAL") } returns "raw-skeptical"
        val parser: (Lens, String) -> LensExtraction? = { lens, raw ->
            if (raw.isEmpty()) null else extraction(lens)
        }
        val listener = RecordingListener()

        val result = BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(resolved),
            parser = parser,
            composer = fakeComposer(),
        ).extract(entryText = "user words", listener = listener)

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
        assertTrue(terminal.lastError!!.startsWith("engine-error:"))
    }

    @Test
    fun `blank entry text fails fast`() {
        val worker = BackgroundExtractionWorker(
            engine = mockk(),
            resolver = RecordingResolver(resolved),
            parser = { _, _ -> null },
            composer = fakeComposer(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { worker.extract(entryText = "   ") }
        }
    }

    @Test
    fun `maxAttemptsPerLens must be positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackgroundExtractionWorker(
                engine = mockk(),
                resolver = RecordingResolver(resolved),
                maxAttemptsPerLens = 0,
            )
        }
    }
}
