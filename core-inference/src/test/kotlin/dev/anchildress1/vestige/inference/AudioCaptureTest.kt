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
 * JVM-level checks for [AudioCapture]. The on-device read loop runs against a real
 * `AudioRecord` only in `PerCapturePersonaSmokeTest` (instrumented); these tests pin the
 * helpers + the precondition checks so the JVM coverage floor is real and the post-fallback
 * 30 s cap behavior is verified without needing the device.
 */
class AudioCaptureTest {

    private val tag = "VestigeAudioCapture"

    @BeforeEach
    fun setUpLogStub() {
        // Robolectric isn't applied here; android.util.Log is final and would throw on JVM.
        // mockkStatic short-circuits the static calls so tryBuildCapChunk's WARN path runs and
        // we can verify the message shape without dragging a Robolectric runtime in.
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
        // With isReturnDefaultValues=true, AudioRecord.getMinBufferSize returns 0.
        // check(0 > 0) raises the device-support error before any emission occurs.
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
        // ChunkBuilder with samplesPerChunk=2 + a 6-sample read produces 3 complete chunks. v1
        // emits only the first; the rest are discarded with a WARN documenting the truncation.
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
        // Simulate AudioRecord.read filling the buffer with 4 samples of audio in one call.
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
        // Big chunk; small reads → never completes within the requested-stop window.
        val builder = ChunkBuilder(samplesPerChunk = 1_000)
        val readBuffer = FloatArray(8)
        val record = mockk<AudioRecord>()
        var reads = 0
        every {
            record.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
        } answers {
            reads += 1
            // After a couple of partial reads, request the stop. The next loop iteration sees
            // stopRequested==true at the head of the while predicate and exits.
            if (reads >= 2) capture.requestStop()
            8
        }

        val chunk = capture.readUntilCapOrStop(record, readBuffer, builder)

        assertNull(chunk, "Loop must exit cleanly when requestStop fires before the cap")
        assertTrue(reads >= 2, "Test setup expected at least two read iterations before stop")
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
    fun `readUntilCapOrStop returns null when read produces 0 samples and stop fires later`() = runTest {
        // Defensive zero-read branch: AudioRecord.READ_BLOCKING can return 0 (no samples ready
        // this tick) and the loop must continue without trying to build a chunk from nothing.
        // After a couple of zero-reads we requestStop; the loop exits null without ever calling
        // tryBuildCapChunk.
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
