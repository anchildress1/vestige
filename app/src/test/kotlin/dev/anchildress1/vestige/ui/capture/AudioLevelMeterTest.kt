package dev.anchildress1.vestige.ui.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLevelMeterTest {

    @Test
    fun `empty samples yield zero level`() {
        val meter = AudioLevelMeter(windowSize = 4)
        val level = meter.push(FloatArray(0))
        assertEquals(0f, level, 0f)
    }

    @Test
    fun `rms of constant signal equals signal magnitude clamped`() {
        val meter = AudioLevelMeter(windowSize = 4)
        val level = meter.push(FloatArray(8) { 0.5f })
        assertEquals(0.5f, level, EPSILON)
    }

    @Test
    fun `levels clip above 1`() {
        val meter = AudioLevelMeter(windowSize = 4)
        val level = meter.push(FloatArray(4) { 5f })
        assertEquals(1f, level, 0f)
    }

    @Test
    fun `window rotates FIFO`() {
        val meter = AudioLevelMeter(windowSize = 3)
        meter.push(FloatArray(2) { 0.2f })
        meter.push(FloatArray(2) { 0.4f })
        meter.push(FloatArray(2) { 0.6f })
        val expectedFirst = listOf(0.2f, 0.4f, 0.6f)
        assertListEquals(expectedFirst, meter.levels)

        meter.push(FloatArray(2) { 0.8f })
        val expectedSecond = listOf(0.4f, 0.6f, 0.8f)
        assertListEquals(expectedSecond, meter.levels)
        assertTrue(meter.isWindowFull())
    }

    @Test
    fun `window reports not-full before rollover`() {
        val meter = AudioLevelMeter(windowSize = 3)
        meter.push(FloatArray(2) { 0.2f })
        assertFalse(meter.isWindowFull())
    }

    @Test
    fun `levels always returns full-size list with zeros for unpushed slots`() {
        val meter = AudioLevelMeter(windowSize = 5)
        meter.push(FloatArray(2) { 0.3f })
        val levels = meter.levels
        assertEquals(5, levels.size)
        // Order is chronological (oldest first); unpushed slots are zero.
        assertEquals(0f, levels[0], 0f)
        assertEquals(0f, levels[1], 0f)
        assertEquals(0f, levels[2], 0f)
        assertEquals(0f, levels[3], 0f)
        assertEquals(0.3f, levels[4], EPSILON)
    }

    @Test
    fun `non-positive window size is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { AudioLevelMeter(windowSize = 0) }
        assertThrows(IllegalArgumentException::class.java) { AudioLevelMeter(windowSize = -1) }
    }

    @Test
    fun `count larger than samples size is rejected`() {
        val meter = AudioLevelMeter(windowSize = 2)
        assertThrows(IllegalArgumentException::class.java) { meter.push(FloatArray(3), count = 4) }
    }

    private fun assertListEquals(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size)
        for ((i, e) in expected.withIndex()) {
            assertEquals("index $i", e, actual[i], EPSILON)
        }
    }

    private companion object {
        const val EPSILON: Float = 1e-4f
    }
}
