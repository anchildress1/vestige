package dev.anchildress1.vestige.ui.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLevelBarsTest {

    @Test
    fun `true silence maps to zero`() {
        assertEquals(0f, perceptualBarLevel(0f), 0f)
        assertEquals(0f, perceptualBarLevel(0.001f), 0f)
    }

    @Test
    fun `any audible input snaps to at least the visible floor`() {
        // RMS just above the silence epsilon — linear would be ~0.005, invisible. Perceptual
        // must lift it to the clearly-visible floor.
        assertTrue(perceptualBarLevel(0.01f) >= 0.18f)
    }

    @Test
    fun `quiet speech expands well above its linear height`() {
        // Conversational RMS ~0.08 would be a stub at linear scale; perceptual must push it
        // past a third of the strip so the meter looks alive.
        assertTrue("expected quiet speech to fill the strip", perceptualBarLevel(0.08f) > 0.33f)
    }

    @Test
    fun `full scale stays saturated and clamps above one`() {
        assertEquals(1f, perceptualBarLevel(1f), 1e-4f)
        assertEquals(1f, perceptualBarLevel(2f), 0f)
    }

    @Test
    fun `mapping is monotonic non-decreasing`() {
        var prev = perceptualBarLevel(0f)
        var x = 0.01f
        while (x <= 1f) {
            val cur = perceptualBarLevel(x)
            assertTrue("non-monotonic at $x ($prev -> $cur)", cur >= prev - 1e-5f)
            prev = cur
            x += 0.01f
        }
    }
}
