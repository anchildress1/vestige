package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.ConfidenceVerdict
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.Lens
import dev.anchildress1.vestige.model.LensExtraction
import dev.anchildress1.vestige.model.ResolvedExtraction
import dev.anchildress1.vestige.model.ResolvedField
import dev.anchildress1.vestige.model.TemplateLabel
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
import java.time.Instant
import java.util.TimeZone

class BackgroundExtractionWorkerTest {

    private val capturedAt: Instant = Instant.parse("2026-05-09T08:00:00Z")
    private val request = BackgroundExtractionRequest(entryText = "user words", capturedAt = capturedAt)
    private val resolved = ResolvedExtraction(
        fields = mapOf(
            "energy_descriptor" to ResolvedField("crashed", ConfidenceVerdict.CANONICAL),
            "state_shift" to ResolvedField(true, ConfidenceVerdict.CANONICAL),
        ),
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
        data class Update(val status: ExtractionStatus, val entryAttemptCount: Int, val lastError: String?)
        val updates: MutableList<Update> = mutableListOf()
        override suspend fun onUpdate(status: ExtractionStatus, entryAttemptCount: Int, lastError: String?) {
            updates += Update(status, entryAttemptCount, lastError)
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

        val result = worker.extract(request = request, listener = listener)

        val success = assertInstanceOf(BackgroundExtractionResult.Success::class.java, result)
        assertEquals(4, success.modelCallCount, "1 retry on LITERAL + 1 each on INFERENTIAL/SKEPTICAL = 4")
        assertEquals(2, success.lensResults.first { it.lens == Lens.LITERAL }.attemptCount)
        // Listener: initial RUNNING(0,null) → retry RUNNING(0,parse-fail) → terminal COMPLETED(0,null).
        // The entry-level retry counter is stable for the whole run; per-lens retries are
        // reported only through the repeated RUNNING update and lens-level diagnostics.
        assertEquals(
            listOf(
                RecordingListener.Update(ExtractionStatus.RUNNING, 0, null),
                RecordingListener.Update(ExtractionStatus.RUNNING, 0, "parse-fail"),
                RecordingListener.Update(ExtractionStatus.COMPLETED, 0, null),
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
        coEvery { engine.generateText(any()) } returns "garbage-always"
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

    @Test
    fun `worker computes the template label from the supplied capture timestamp`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        coEvery { engine.generateText(any()) } returns "raw-ok"
        val lateNightResolved = ResolvedExtraction(
            fields = mapOf("tags" to ResolvedField(listOf("late-night"), ConfidenceVerdict.CANONICAL)),
        )
        val parser: (Lens, String) -> LensExtraction? = { lens, _ -> extraction(lens) }
        val originalZone = TimeZone.getDefault()
        try {
            // Pin the JVM default zone so the worker's default TemplateLabeler is deterministic:
            // 08:00 UTC is 03:00 in Chicago, which sits inside the goblin window.
            TimeZone.setDefault(TimeZone.getTimeZone("America/Chicago"))

            val result = BackgroundExtractionWorker(
                engine = engine,
                resolver = RecordingResolver(lateNightResolved),
                parser = parser,
                composer = fakeComposer(),
            ).extract(
                request = BackgroundExtractionRequest(
                    entryText = "user words",
                    capturedAt = Instant.parse("2026-05-09T08:00:00Z"),
                ),
            )

            val success = assertInstanceOf(BackgroundExtractionResult.Success::class.java, result)
            assertEquals(TemplateLabel.GOBLIN_HOURS, success.templateLabel)
        } finally {
            TimeZone.setDefault(originalZone)
        }
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
            kotlinx.coroutines.runBlocking {
                worker.extract(BackgroundExtractionRequest(entryText = "   ", capturedAt = capturedAt))
            }
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

    @Test
    fun `listener preserves caller supplied entry attempt count across lens retries`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        coEvery { engine.generateText("prompt-for-LITERAL") } returnsMany listOf("garbage-1", "raw-literal")
        coEvery { engine.generateText("prompt-for-INFERENTIAL") } returns "raw-inferential"
        coEvery { engine.generateText("prompt-for-SKEPTICAL") } returns "raw-skeptical"
        val parser: (Lens, String) -> LensExtraction? = { lens, raw ->
            if (raw == "garbage-1") null else extraction(lens)
        }
        val listener = RecordingListener()

        BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(resolved),
            parser = parser,
            composer = fakeComposer(),
        ).extract(
            request = BackgroundExtractionRequest(
                entryText = "user words",
                capturedAt = capturedAt,
                entryAttemptCount = 2,
            ),
            listener = listener,
        )

        assertEquals(
            listOf(
                RecordingListener.Update(ExtractionStatus.RUNNING, 2, null),
                RecordingListener.Update(ExtractionStatus.RUNNING, 2, "parse-fail"),
                RecordingListener.Update(ExtractionStatus.COMPLETED, 2, null),
            ),
            listener.updates,
        )
    }

    @Test
    fun `negative entry attempt count fails fast`() {
        val worker = BackgroundExtractionWorker(
            engine = mockk(),
            resolver = RecordingResolver(resolved),
            parser = { _, _ -> null },
            composer = fakeComposer(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                worker.extract(
                    BackgroundExtractionRequest(
                        entryText = "ok",
                        capturedAt = capturedAt,
                        entryAttemptCount = -1,
                    ),
                )
            }
        }
    }

    @Test
    fun `non-positive timeout fails fast`() {
        val worker = BackgroundExtractionWorker(
            engine = mockk(),
            resolver = RecordingResolver(resolved),
            parser = { _, _ -> null },
            composer = fakeComposer(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                worker.extract(BackgroundExtractionRequest(entryText = "ok", capturedAt = capturedAt, timeoutMs = 0L))
            }
        }
    }

    @Test
    fun `resolver throwing emits terminal FAILED instead of leaving status RUNNING`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        coEvery { engine.generateText(any()) } returns "raw-ok"
        val parser: (Lens, String) -> LensExtraction? = { lens, _ -> extraction(lens) }
        val throwingResolver = object : ConvergenceResolver {
            override fun resolve(extractions: List<LensExtraction>): ResolvedExtraction = error("resolver-explosion")
        }
        val listener = RecordingListener()

        val result = BackgroundExtractionWorker(
            engine = engine,
            resolver = throwingResolver,
            parser = parser,
            composer = fakeComposer(),
        ).extract(request = request, listener = listener)

        val failed = assertInstanceOf(BackgroundExtractionResult.Failed::class.java, result)
        assertTrue(failed.lastError.startsWith("resolver-error:"))
        // Persistence layer needs the terminal transition — without it the entry stalls in RUNNING.
        assertEquals(ExtractionStatus.FAILED, listener.updates.last().status)
        assertTrue(listener.updates.last().lastError!!.startsWith("resolver-error:"))
    }

    @Test
    fun `timeout produces TimedOut with whatever lens results completed before the cap`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        // First lens completes; second lens hangs forever; the cap fires before the third runs.
        coEvery { engine.generateText("prompt-for-LITERAL") } returns "raw-literal"
        coEvery { engine.generateText("prompt-for-INFERENTIAL") } coAnswers {
            kotlinx.coroutines.delay(Long.MAX_VALUE / 2)
            "never"
        }
        val parser: (Lens, String) -> LensExtraction? = { lens, raw ->
            if (raw == "raw-literal") extraction(lens) else null
        }
        val listener = RecordingListener()

        val result = BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(resolved),
            parser = parser,
            composer = fakeComposer(),
        ).extract(
            request = BackgroundExtractionRequest(entryText = "user words", capturedAt = capturedAt, timeoutMs = 50L),
            listener = listener,
        )

        val timedOut = assertInstanceOf(BackgroundExtractionResult.TimedOut::class.java, result)
        assertEquals(50L, timedOut.timeoutMs)
        // LITERAL completed before the cap; INFERENTIAL was in-flight, so it doesn't appear yet.
        assertEquals(listOf(Lens.LITERAL), timedOut.lensResults.map { it.lens })
        val terminal = listener.updates.last()
        assertEquals(ExtractionStatus.TIMED_OUT, terminal.status)
        assertEquals("timeout-after-50ms", terminal.lastError)
    }
}
