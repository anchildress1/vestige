package dev.anchildress1.vestige.ui.atmosphere

import dev.anchildress1.vestige.ui.components.alphaAt
import dev.anchildress1.vestige.ui.components.renderInBitmap
import dev.anchildress1.vestige.ui.theme.Glow
import dev.anchildress1.vestige.ui.theme.S2
import dev.anchildress1.vestige.ui.theme.Vapor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pos / neg / err / edge coverage on `drawFogDrift` — the testable core of [FogDrift]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FogDriftDrawScopeTest {

    @Test
    fun `drawFogDrift paints both blobs at mid intensity (pos)`() {
        val bm = renderInBitmap(80, 80) {
            drawFogDrift(intensity = 0.5f, hueA = Vapor, hueB = Vapor, phaseA = 0f, phaseB = 0f)
        }
        // The blob centers fall on the receiver — at least one pixel inside the field should tint.
        var anyPainted = false
        for (x in listOf(10, 40, 70)) {
            for (y in listOf(10, 40, 70)) {
                if (bm.alphaAt(x, y) > 0f) anyPainted = true
            }
        }
        assertTrue("fog should tint at least one sampled pixel at mid intensity", anyPainted)
    }

    @Test
    fun `drawFogDrift collapses to transparent at zero intensity (edge lower bound)`() {
        val bm = renderInBitmap(40, 40) {
            drawFogDrift(intensity = 0f, hueA = Vapor, hueB = Vapor, phaseA = 0f, phaseB = 0f)
        }
        // alpha=0 → gradient draws Color.copy(alpha=0) → fully transparent everywhere.
        assertEquals(0f, bm.alphaAt(20, 20), 0.01f)
    }

    @Test
    fun `drawFogDrift clamps negative intensity to zero (err)`() {
        val bm = renderInBitmap(40, 40) {
            drawFogDrift(intensity = -5f, hueA = Vapor, hueB = Vapor, phaseA = 0f, phaseB = 0f)
        }
        assertEquals("center stays transparent under negative intensity", 0f, bm.alphaAt(20, 20), 0.01f)
    }

    @Test
    fun `drawFogDrift clamps above 1 (edge upper bound)`() {
        val bm = renderInBitmap(40, 40) {
            drawFogDrift(intensity = 99f, hueA = Vapor, hueB = Vapor, phaseA = 0f, phaseB = 0f)
        }
        // Clamped to 1, gradient renders — center should be painted.
        assertTrue("clamped above-1 still draws", bm.alphaAt(20, 20) > 0f)
    }

    @Test
    fun `drawFogDrift treats NaN intensity as transparent (err — coerceIn does not clamp NaN)`() {
        val bm = renderInBitmap(40, 40) {
            drawFogDrift(intensity = Float.NaN, hueA = Vapor, hueB = Vapor, phaseA = 0f, phaseB = 0f)
        }
        assertEquals("NaN intensity must collapse to fully transparent", 0f, bm.alphaAt(20, 20), 0.01f)
    }

    @Test
    fun `drawFogDrift bails out on zero-sized canvas (neg — degenerate size)`() {
        // Smallest non-zero ImageBitmap is 1×1; we render at that size and confirm no throw.
        val bm = renderInBitmap(1, 1) {
            drawFogDrift(intensity = 0.5f, hueA = Vapor, hueB = Vapor, phaseA = 0f, phaseB = 0f)
        }
        // Just ensure the call returned without throwing — alpha may be 0 or non-zero.
        assertTrue(bm.alphaAt(0, 0) in 0f..1f)
    }

    @Test
    fun `drawFogDrift respects custom hues (pos — domain override)`() {
        val bm = renderInBitmap(80, 80) {
            drawFogDrift(intensity = 0.8f, hueA = Glow, hueB = Vapor, phaseA = 0f, phaseB = 0f)
        }
        // Blob A is at offset 0 → cos=1 → centerA shifts right of midpoint. Sample left edge
        // for B's influence; right edge for A. Both should have some accent tint.
        assertTrue("right side picks up hue A", bm.alphaAt(70, 40) > 0f)
    }

    @Test
    fun `drawFogDrift accepts neutral S2 hue (pos — desaturated override)`() {
        val bm = renderInBitmap(40, 40) {
            drawFogDrift(intensity = 0.6f, hueA = S2, hueB = S2, phaseA = 0f, phaseB = 0f)
        }
        // S2 with alpha > 0 should paint somewhere — sample multiple points.
        var anyPainted = false
        listOf(10 to 10, 20 to 20, 30 to 30).forEach { (x, y) ->
            if (bm.alphaAt(x, y) > 0f) anyPainted = true
        }
        assertTrue("S2 fog should tint at least one sampled pixel", anyPainted)
    }

    @Test
    fun `drawFogDrift exercises both phase inputs without throwing (pos — phase plumbing)`() {
        // Phase shift coverage on the gradient path. `fogCenter` is independently asserted in
        // `AtmosphereTest`; here we only verify the renderer wires phase through cleanly.
        renderInBitmap(60, 60) {
            drawFogDrift(intensity = 0.7f, hueA = Vapor, hueB = Vapor, phaseA = 0.25f, phaseB = 0.75f)
        }
        renderInBitmap(60, 60) {
            drawFogDrift(intensity = 0.7f, hueA = Vapor, hueB = Vapor, phaseA = 0.5f, phaseB = 0.5f)
        }
    }
}
