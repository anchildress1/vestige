package dev.anchildress1.vestige.inference

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class AudioChunkTest {

    @Test
    fun `durationMs reflects sample count and rate`() {
        val chunk = AudioChunk(
            samples = FloatArray(16_000),
            sampleRateHz = 16_000,
            isFinal = false,
        )
        assertEquals(1_000L, chunk.durationMs)
    }

    @Test
    fun `durationMs is zero for an empty chunk`() {
        val chunk = AudioChunk(samples = FloatArray(0), sampleRateHz = 16_000, isFinal = true)
        assertEquals(0L, chunk.durationMs)
    }

    @Test
    fun `equals treats samples as content-equal`() {
        val a = AudioChunk(floatArrayOf(0.1f, 0.2f), 16_000, isFinal = false)
        val b = AudioChunk(floatArrayOf(0.1f, 0.2f), 16_000, isFinal = false)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `equals discriminates on isFinal`() {
        val a = AudioChunk(floatArrayOf(0.1f), 16_000, isFinal = false)
        val b = AudioChunk(floatArrayOf(0.1f), 16_000, isFinal = true)
        assertNotEquals(a, b)
    }

    @Test
    fun `equals discriminates on sample-rate mismatch`() {
        val a = AudioChunk(floatArrayOf(0.1f), 16_000, isFinal = false)
        val b = AudioChunk(floatArrayOf(0.1f), 8_000, isFinal = false)
        assertNotEquals(a, b)
    }
}
