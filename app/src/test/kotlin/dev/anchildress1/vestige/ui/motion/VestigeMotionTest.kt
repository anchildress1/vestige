package dev.anchildress1.vestige.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Pins the sb* keyframe periods to ADR-011 + poc/energy-tokens.jsx. */
class VestigeMotionTest {

    @Test fun `pulse period is 1_400 ms`() = assertEquals(1_400, VestigeMotion.PULSE_MS)

    @Test fun `blink period is 800 ms`() = assertEquals(800, VestigeMotion.BLINK_MS)

    @Test fun `bars period is 1_000 ms`() = assertEquals(1_000, VestigeMotion.BARS_MS)

    @Test fun `sweep period is 1_800 ms`() = assertEquals(1_800, VestigeMotion.SWEEP_MS)

    @Test fun `wobble period is 2_600 ms`() = assertEquals(2_600, VestigeMotion.WOBBLE_MS)

    @Test fun `scroll period is 18_000 ms`() = assertEquals(18_000, VestigeMotion.SCROLL_MS)

    @Test fun `tick period is 320 ms`() = assertEquals(320, VestigeMotion.TICK_MS)

    @Test
    fun `one-shot specs are wired`() {
        assertNotNull(VestigeMotion.Rise)
        assertNotNull(VestigeMotion.Out)
        assertNotNull(VestigeMotion.Fade)
        assertNotNull(VestigeMotion.Slide)
    }
}
