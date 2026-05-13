package dev.anchildress1.vestige.ui.atmosphere

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/** Locks the pure pieces of the atmospheric layer — image construction needs an Android runtime. */
class AtmosphereTest {

    @Test
    fun `grain opacity clamps below the floor`() {
        assertEquals(NOISE_GRAIN_MIN_OPACITY, clampGrainOpacity(0f))
        assertEquals(NOISE_GRAIN_MIN_OPACITY, clampGrainOpacity(-0.5f))
    }

    @Test
    fun `grain opacity clamps above the ceiling`() {
        assertEquals(NOISE_GRAIN_MAX_OPACITY, clampGrainOpacity(0.99f))
        assertEquals(NOISE_GRAIN_MAX_OPACITY, clampGrainOpacity(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `grain opacity passes through inside the band`() {
        assertEquals(0.10f, clampGrainOpacity(0.10f))
        assertEquals(NOISE_GRAIN_MIN_OPACITY, clampGrainOpacity(NOISE_GRAIN_MIN_OPACITY))
        assertEquals(NOISE_GRAIN_MAX_OPACITY, clampGrainOpacity(NOISE_GRAIN_MAX_OPACITY))
    }

    @Test
    fun `noise pixels are deterministic per seed`() {
        val a = noiseAlphaPixels(NOISE_DEFAULT_SEED)
        val b = noiseAlphaPixels(NOISE_DEFAULT_SEED)
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun `noise pixels diverge across seeds`() {
        val a = noiseAlphaPixels(seed = 1)
        val b = noiseAlphaPixels(seed = 2)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `noise pixels span the 180x180 tile`() {
        assertEquals(NOISE_TILE_PX * NOISE_TILE_PX, noiseAlphaPixels(NOISE_DEFAULT_SEED).size)
    }

    @Test
    fun `fog periods are 22s and 28s`() {
        assertEquals(22_000, FOG_PERIOD_A_MS)
        assertEquals(28_000, FOG_PERIOD_B_MS)
    }

    @Test
    fun `fog center swings around the surface midpoint`() {
        val w = 100f
        val h = 100f
        // phase 0, offset 0 → cos=1, sin=0 → center shifts right by w*swing
        val zero = fogCenter(phase = 0f, w = w, h = h, swing = 0.2f, offset = 0f)
        assertEquals(50f + 20f, zero.x, 0.001f)
        assertEquals(50f, zero.y, 0.001f)

        // phase 0.25 → cos≈0, sin≈1 → drifts down
        val quarter = fogCenter(phase = 0.25f, w = w, h = h, swing = 0.2f, offset = 0f)
        assertEquals(50f, quarter.x, 0.01f)
        assertEquals(50f + 20f, quarter.y, 0.01f)
    }

    @Test
    fun `fog offset phase-shifts the second blob into anti-phase`() {
        // Blob B uses offset = π so its center mirrors blob A through the surface midpoint.
        val w = 100f
        val h = 100f
        val a = fogCenter(phase = 0f, w = w, h = h, swing = 0.2f, offset = 0f)
        val b = fogCenter(phase = 0f, w = w, h = h, swing = 0.2f, offset = PI.toFloat())
        // a.x = 50 + 20 = 70, b.x = 50 - 20 = 30
        assertEquals(70f, a.x, 0.01f)
        assertEquals(30f, b.x, 0.01f)
    }
}
