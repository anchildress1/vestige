package dev.anchildress1.vestige.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Pins the ves* keyframe periods to the values in poc/design-review.md §2.5. */
class VestigeMotionTest {

    @Test fun `pulse period is 1_400 ms`() = assertEquals(1_400, VestigeMotion.PULSE_MS)

    @Test fun `breath period is 6_000 ms`() = assertEquals(6_000, VestigeMotion.BREATH_MS)

    @Test fun `shimmer period is 1_800 ms`() = assertEquals(1_800, VestigeMotion.SHIMMER_MS)

    @Test fun `spin period is 16_000 ms`() = assertEquals(16_000, VestigeMotion.SPIN_MS)

    @Test
    fun `one-shot specs are wired`() {
        assertNotNull(VestigeMotion.In)
        assertNotNull(VestigeMotion.Out)
        assertNotNull(VestigeMotion.Fade)
        assertNotNull(VestigeMotion.Slide)
    }
}
