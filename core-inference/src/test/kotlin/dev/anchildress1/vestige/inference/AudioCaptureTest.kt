package dev.anchildress1.vestige.inference

import android.media.AudioRecord
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * JVM-level checks for [AudioCapture]. The full `captureChunks` flow against a real
 * `AudioRecord` runs in `PerCapturePersonaSmokeTest` (instrumented).
 */
class AudioCaptureTest {

    private val tag = "VestigeAudioCapture"

    @BeforeEach
    fun setUpLogStub() {
        // android.util.Log is final and throws on JVM without a shadow; mockkStatic lets the
        // tryBuildCapChunk WARN path run without pulling in Robolectric.
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @AfterEach
    fun tearDownLogStub() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `SAMPLE_RATE_HZ is 16 kHz per Gemma 4 audio spec`() {
        assertEquals(16_000, AudioCapture.SAMPLE_RATE_HZ)
    }

    @Test
    fun `CHUNK_DURATION_MS is 30 seconds per ADR-001 Q4`() {
        assertEquals(30_000L, AudioCapture.CHUNK_DURATION_MS)
    }

    @Test
    fun `requestStop is callable on a fresh instance without error`() {
        val capture = AudioCapture()
        capture.requestStop()
    }

    @Test
    fun `requestStop is idempotent`() {
        val capture = AudioCapture()
        capture.requestStop()
        capture.requestStop()
    }

    @Test
    fun `captureChunks throws when AudioRecord reports an unsupported format`() = runTest {
        val capture = AudioCapture()
        val result = runCatching { capture.captureChunks().collect {} }
        assertTrue(result.isFailure) { "Expected failure when AudioRecord cannot initialize" }
        val ex = result.exceptionOrNull()
        assertTrue(ex is IllegalStateException) { "Expected IllegalStateException, got ${ex?.javaClass?.name}" }
        assertTrue(ex!!.message!!.contains("AudioRecord.getMinBufferSize returned")) {
            "Error message should identify the failed API call: ${ex.message}"
        }
    }

    @Test
    fun `tryBuildCapChunk returns null when builder produces no complete chunk`() {
        val capture = AudioCapture(sampleRateHz = 16_000, chunkDurationMs = 30_000L)
        val builder = ChunkBuilder(samplesPerChunk = 100)
        val readBuffer = FloatArray(40) { it.toFloat() }

        val chunk = capture.tryBuildCapChunk(builder, readBuffer, readCount = 40)

        assertNull(chunk, "Below the chunk threshold the helper must defer the emission")
        verify(exactly = 0) { Log.w(any<String>(), any<String>()) }
    }

    @Test
    fun `tryBuildCapChunk returns AudioChunk when builder completes exactly one chunk`() {
        val capture = AudioCapture(sampleRateHz = 16_000, chunkDurationMs = 30_000L)
        val builder = ChunkBuilder(samplesPerChunk = 4)
        val readBuffer = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)

        val chunk = capture.tryBuildCapChunk(builder, readBuffer, readCount = 4)

        assertTrue(chunk != null, "Single complete chunk must surface as the cap emission")
        chunk!!
        assertTrue(chunk.isFinal, "Cap chunk is the only emission per recording — must be final")
        assertEquals(16_000, chunk.sampleRateHz)
        assertArrayEquals(readBuffer, chunk.samples)
        verify(exactly = 0) { Log.w(any<String>(), any<String>()) }
    }

    @Test
    fun `tryBuildCapChunk emits first chunk and WARN-logs when builder yields multiple complete chunks`() {
        val capture = AudioCapture(sampleRateHz = 16_000, chunkDurationMs = 30_000L)
        val builder = ChunkBuilder(samplesPerChunk = 2)
        val readBuffer = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)

        val chunk = capture.tryBuildCapChunk(builder, readBuffer, readCount = 6)

        assertTrue(chunk != null, "Multi-chunk read must still emit the first complete chunk")
        chunk!!
        assertTrue(chunk.isFinal)
        assertArrayEquals(floatArrayOf(1f, 2f), chunk.samples)
        verify(exactly = 1) {
            Log.w(
                tag,
                match<String> { it.contains("30s cap fired") && it.contains("2 tail chunk(s) discarded") },
            )
        }
    }

    @Test
    fun `readUntilCapOrStop returns cap chunk on the first read that completes a window`() = runTest {
        val capture = AudioCapture(sampleRateHz = 16_000, chunkDurationMs = 30_000L)
        val builder = ChunkBuilder(samplesPerChunk = 4)
        val readBuffer = FloatArray(4)
        val record = mockk<AudioRecord>()
        every {
            record.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
        } answers {
            readBuffer[0] = 0.1f
            readBuffer[1] = 0.2f
            readBuffer[2] = 0.3f
            readBuffer[3] = 0.4f
            4
        }

        val chunk = capture.readUntilCapOrStop(record, readBuffer, builder)

        assertTrue(chunk != null, "Cap should fire on first complete chunk")
        chunk!!
        assertTrue(chunk.isFinal)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), chunk.samples)
    }

    @Test
    fun `readUntilCapOrStop returns null when requestStop fires before the cap`() = runTest {
        val capture = AudioCapture(sampleRateHz = 16_000, chunkDurationMs = 30_000L)
        val builder = ChunkBuilder(samplesPerChunk = 1_000)
        val readBuffer = FloatArray(8)
        val record = mockk<AudioRecord>()
        var reads = 0
        every {
            record.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
        } answers {
            reads += 1
            if (reads >= 2) capture.requestStop()
            8
        }

        val chunk = capture.readUntilCapOrStop(record, readBuffer, builder)

        assertNull(chunk, "Loop must exit cleanly when requestStop fires before the cap")
        assertTrue(reads >= 2, "Test setup expected at least two read iterations before stop")
    }

    @Test
    fun `readUntilCapOrStop exits immediately when stop was requested before capture starts`() = runTest {
        val capture = AudioCapture(sampleRateHz = 16_000, chunkDurationMs = 30_000L)
        val builder = ChunkBuilder(samplesPerChunk = 1_000)
        val readBuffer = FloatArray(8)
        val record = mockk<AudioRecord>()
        capture.requestStop()

        val chunk = capture.readUntilCapOrStop(record, readBuffer, builder)

        assertNull(chunk, "Pre-start stop must stay sticky for this recording session")
        verify(exactly = 0) {
            record.read(any<FloatArray>(), any(), any(), any())
        }
    }

    @Test
    fun `readUntilCapOrStop throws when AudioRecord_read returns a negative error code`() = runTest {
        val capture = AudioCapture(sampleRateHz = 16_000, chunkDurationMs = 30_000L)
        val builder = ChunkBuilder(samplesPerChunk = 1_000)
        val readBuffer = FloatArray(8)
        val record = mockk<AudioRecord>()
        every {
            record.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
        } returns AudioRecord.ERROR_INVALID_OPERATION

        val outcome = runCatching { capture.readUntilCapOrStop(record, readBuffer, builder) }
        val ex = outcome.exceptionOrNull()
        assertTrue(ex is IllegalStateException) {
            "Expected IllegalStateException from negative read code; got ${ex?.javaClass?.name}"
        }
        assertTrue(
            ex!!.message!!.contains("AudioRecord.read returned error code"),
            "Error must identify the failing API + the negative read result; was: ${ex.message}",
        )
    }

    @Test
    fun `readUntilCapOrStop returns null when stop interrupts a blocking read`() = runTest {
        val capture = AudioCapture(sampleRateHz = 16_000, chunkDurationMs = 30_000L)
        val builder = ChunkBuilder(samplesPerChunk = 1_000)
        val readBuffer = FloatArray(8)
        val record = mockk<AudioRecord>()
        every {
            record.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
        } answers {
            capture.requestStop()
            AudioRecord.ERROR_INVALID_OPERATION
        }

        val chunk = capture.readUntilCapOrStop(record, readBuffer, builder)

        assertNull(chunk, "Stop-triggered AudioRecord interruption must finish cleanly")
    }

    @Test
    fun `onLevel callback fires for every non-empty read`() = runTest {
        val levels = mutableListOf<Float>()
        val capture = AudioCapture(
            sampleRateHz = 16_000,
            chunkDurationMs = 30_000L,
            onLevel = { levels += it },
        )
        val builder = ChunkBuilder(samplesPerChunk = 4)
        val readBuffer = FloatArray(4)
        val record = mockk<AudioRecord>()
        every {
            record.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
        } answers {
            readBuffer[0] = 0.5f
            readBuffer[1] = 0.5f
            readBuffer[2] = 0.5f
            readBuffer[3] = 0.5f
            4
        }

        capture.readUntilCapOrStop(record, readBuffer, builder)

        assertTrue(levels.size == 1, "onLevel should fire once per non-empty read (was ${levels.size})")
        // RMS of constant 0.5 ≈ 0.5; allow generous tolerance for the float-to-double round-trip.
        assertTrue(levels[0] > 0.4f && levels[0] < 0.6f, "Expected ~0.5 RMS, was ${levels[0]}")
    }

    @Test
    fun `onLevel callback is skipped for zero-byte reads`() = runTest {
        val levels = mutableListOf<Float>()
        val capture = AudioCapture(
            sampleRateHz = 16_000,
            chunkDurationMs = 30_000L,
            onLevel = { levels += it },
        )
        val builder = ChunkBuilder(samplesPerChunk = 1_000)
        val readBuffer = FloatArray(8)
        val record = mockk<AudioRecord>()
        var reads = 0
        every {
            record.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
        } answers {
            reads += 1
            if (reads >= 2) capture.requestStop()
            0
        }

        capture.readUntilCapOrStop(record, readBuffer, builder)

        assertTrue(levels.isEmpty(), "Zero-read iterations must not emit a level (was ${levels.size})")
    }

    @Test
    fun `rmsLevel clamps to range and returns 0 for empty input`() {
        val capture = AudioCapture()
        assertEquals(0f, capture.rmsLevel(FloatArray(0), 0))
        assertEquals(0f, capture.rmsLevel(FloatArray(4) { 0f }, 4))
        // Constant 0.5 → RMS 0.5 within tolerance.
        val mid = capture.rmsLevel(FloatArray(8) { 0.5f }, 8)
        assertTrue(mid > 0.49f && mid < 0.51f, "Expected ~0.5 RMS, was $mid")
        // Out-of-range samples → clamped to 1.
        val clipped = capture.rmsLevel(FloatArray(4) { 5f }, 4)
        assertEquals(1f, clipped)
    }

    // ─── releaseAndOverwrite (Q8 — synchronous cleanup) ─────────────────────

    @Test
    fun `releaseAndOverwrite zeroes the read buffer and clears the builder (pos)`() {
        val capture = AudioCapture()
        val record = mockk<AudioRecord>(relaxed = true)
        val readBuffer = FloatArray(8) { (it + 1).toFloat() }
        val builder = ChunkBuilder(samplesPerChunk = 16).apply {
            append(floatArrayOf(0.1f, 0.2f, 0.3f), count = 3)
        }

        capture.releaseAndOverwrite(record, readBuffer, builder)

        verify { record.release() }
        assertTrue(readBuffer.all { it == 0f }, "read buffer must be zeroed after cleanup")
        assertNull(builder.drainFinal(), "builder must be cleared after cleanup")
    }

    @Test
    fun `releaseAndOverwrite logs a warning when release exceeds the 100ms budget (edge — slow JNI)`() {
        val capture = AudioCapture()
        val record = mockk<AudioRecord>()
        every { record.release() } answers { Thread.sleep(120) }
        val readBuffer = FloatArray(4)
        val builder = ChunkBuilder(samplesPerChunk = 4)

        capture.releaseAndOverwrite(record, readBuffer, builder)

        verify { Log.w(tag, match<String> { it.contains("release()") && it.contains("budget") }) }
    }

    @Test
    fun `releaseAndOverwrite does not log when release completes inside the budget (neg)`() {
        val capture = AudioCapture()
        val record = mockk<AudioRecord>(relaxed = true)
        val readBuffer = FloatArray(4)
        val builder = ChunkBuilder(samplesPerChunk = 4)

        capture.releaseAndOverwrite(record, readBuffer, builder)

        verify(exactly = 0) { Log.w(tag, match<String> { it.contains("budget") }) }
    }

    @Test
    fun `readUntilCapOrStop returns null when read produces 0 samples and stop fires later`() = runTest {
        // AudioRecord.READ_BLOCKING can legitimately return 0; the loop must not synthesize a chunk.
        val capture = AudioCapture(sampleRateHz = 16_000, chunkDurationMs = 30_000L)
        val builder = ChunkBuilder(samplesPerChunk = 1_000)
        val readBuffer = FloatArray(8)
        val record = mockk<AudioRecord>()
        var reads = 0
        every {
            record.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
        } answers {
            reads += 1
            if (reads >= 2) capture.requestStop()
            0
        }

        val chunk = capture.readUntilCapOrStop(record, readBuffer, builder)

        assertNull(chunk, "Zero-read iterations must not synthesize a chunk; stop ends the loop")
        assertTrue(reads >= 2)
    }
}
