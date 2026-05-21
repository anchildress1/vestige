package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.Lens
import dev.anchildress1.vestige.model.LensExtraction
import dev.anchildress1.vestige.model.ResolvedExtraction
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackgroundExtractionWorkerLifecycleTest {

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
    fun `lenses list must not be empty`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackgroundExtractionWorker(
                engine = mockk(),
                resolver = RecordingResolver(resolved),
                lenses = emptyList(),
            )
        }
    }

    @Test
    fun `terminal listener events carry the caller-supplied entry attempt count`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText("prompt-for-LITERAL", any()) } returnsMany
            listOf(flowOf("garbage-1"), flowOf("raw-literal"))
        every { engine.streamText("prompt-for-INFERENTIAL", any()) } returns flowOf("raw-inferential")
        every { engine.streamText("prompt-for-SKEPTICAL", any()) } returns flowOf("raw-skeptical")
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

        // Even with a LITERAL retry, the caller's entryAttemptCount=2 rides every emitted event;
        // Per-lens retries no longer emit their own status.
        assertEquals(
            listOf(
                RecordingListener.Update(ExtractionStatus.RUNNING, 2, null),
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
        every { engine.streamText(any(), any()) } returns flowOf("raw-ok")
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
        // Sequential run: LITERAL completes; INFERENTIAL hangs past the cap, so only LITERAL lands
        // in the completed accumulator before timeout cancellation wins.
        every { engine.streamText("prompt-for-LITERAL", any()) } returns flowOf("raw-literal")
        every { engine.streamText("prompt-for-INFERENTIAL", any()) } returns flow {
            delay(Long.MAX_VALUE / 2)
            emit("never")
        }
        every { engine.streamText("prompt-for-SKEPTICAL", any()) } returns flow {
            delay(Long.MAX_VALUE / 2)
            emit("never")
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
        // LITERAL completed before the cap; INFERENTIAL was in-flight when the timeout cancelled
        // the run, so only LITERAL is in the accumulator.
        assertEquals(listOf(Lens.LITERAL), timedOut.lensResults.map { it.lens })
        val terminal = listener.updates.last()
        assertEquals(ExtractionStatus.TIMED_OUT, terminal.status)
        assertEquals("timeout-after-50ms", terminal.lastError)
    }
}
