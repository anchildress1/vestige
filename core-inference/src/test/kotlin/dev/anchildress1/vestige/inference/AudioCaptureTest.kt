package dev.anchildress1.vestige.inference

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit-level checks for [AudioCapture] that don't require a real microphone or recording session.
 * The pos-path (capture loop → flow emission → transcription) is an on-device androidTest.
 */
class AudioCaptureTest {

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
}
