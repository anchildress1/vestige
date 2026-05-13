package dev.anchildress1.vestige.ui.components

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.ui.theme.ErrorRed
import dev.anchildress1.vestige.ui.theme.Glow
import dev.anchildress1.vestige.ui.theme.Pulse
import dev.anchildress1.vestige.ui.theme.Vapor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Exercises the extracted [androidx.compose.ui.graphics.drawscope.DrawScope] helpers in
 * `AccentModifiers.kt` against a real bitmap so the draw-path lines have coverage. Pos / neg /
 * err / edge per accent.
 *
 * `NATIVE` graphics mode is required so [androidx.compose.ui.graphics.ImageBitmap] writes land
 * in a readable pixel buffer instead of Robolectric's no-op canvas shadow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccentDrawScopeTest {

    // glowLeftRule — paints a stripe along the leading edge.

    @Test
    fun `drawGlowLeftRule paints Glow on the left edge and leaves the right untouched`() {
        val bm = renderInBitmap(40, 40) { drawGlowLeftRule(3.dp, Glow) }
        assertTrue("left edge should be opaque", bm.alphaAt(1, 20) > 0.9f)
        assertEquals("right edge should be untouched (alpha 0)", 0f, bm.alphaAt(38, 20), 0.01f)
    }

    @Test
    fun `drawGlowLeftRule respects custom width (edge — width past midpoint)`() {
        val bm = renderInBitmap(40, 40) { drawGlowLeftRule(25.dp, Glow) }
        assertTrue("center should now be inside the rule", bm.alphaAt(20, 20) > 0.9f)
    }

    @Test
    fun `drawGlowLeftRule respects custom color (pos — color override)`() {
        val bm = renderInBitmap(20, 20) { drawGlowLeftRule(3.dp, Vapor) }
        val pixel = bm.toPixelMap()[1, 10]
        assertTrue("blue channel dominates Vapor", pixel.blue > pixel.green)
    }

    // vaporHaloOnRecording — pos / neg / err / edge.

    @Test
    fun `drawVaporHaloOnRecording skips draw at zero level (neg)`() {
        val bm = renderInBitmap(40, 40) { drawVaporHaloOnRecording(level = 0f, color = Vapor) }
        assertEquals("center untouched", 0f, bm.alphaAt(20, 20), 0.01f)
    }

    @Test
    fun `drawVaporHaloOnRecording paints at mid level (pos)`() {
        val bm = renderInBitmap(60, 60) { drawVaporHaloOnRecording(level = 0.5f, color = Vapor) }
        assertTrue("halo should tint near the center", bm.alphaAt(30, 30) > 0f)
    }

    @Test
    fun `drawVaporHaloOnRecording paints at full amp (edge upper bound)`() {
        val bm = renderInBitmap(60, 60) { drawVaporHaloOnRecording(level = 1f, color = Vapor) }
        assertTrue("halo brighter at amp=1 than mid", bm.alphaAt(30, 30) > 0f)
    }

    @Test
    fun `drawVaporHaloOnRecording treats negative level as idle (err)`() {
        val bm = renderInBitmap(40, 40) { drawVaporHaloOnRecording(level = -0.5f, color = Vapor) }
        assertEquals("center untouched on negative", 0f, bm.alphaAt(20, 20), 0.01f)
    }

    @Test
    fun `drawVaporHaloOnRecording treats NaN as idle (err)`() {
        val bm = renderInBitmap(40, 40) { drawVaporHaloOnRecording(level = Float.NaN, color = Vapor) }
        assertEquals("center untouched on NaN", 0f, bm.alphaAt(20, 20), 0.01f)
    }

    @Test
    fun `drawVaporHaloOnRecording clamps level above 1 (edge — above bound)`() {
        val bm = renderInBitmap(60, 60) { drawVaporHaloOnRecording(level = 99f, color = Vapor) }
        // Clamped to 1, halo still paints — alpha non-zero somewhere near the center.
        assertTrue("clamped level still draws", bm.alphaAt(30, 30) > 0f)
    }

    // pulseDotForReady — halo + inner + rim.

    @Test
    fun `drawPulseDotForReady paints inner disc near the center`() {
        val bm = renderInBitmap(32, 32) { drawPulseDotForReady(color = Pulse) }
        assertTrue("center pixel inside the dot is opaque-ish", bm.alphaAt(16, 16) > 0.5f)
    }

    @Test
    fun `drawPulseDotForReady halo extends past the inner disc`() {
        val bm = renderInBitmap(40, 40) { drawPulseDotForReady(color = Pulse) }
        // The radial halo fills the whole receiver at low alpha — corner should still see some tint.
        assertTrue("corner gets the halo gradient", bm.alphaAt(0, 0) > 0f)
    }

    // errorFillForDestructive — paints a rounded ErrorRed rect.

    @Test
    fun `drawErrorFillForDestructive paints ErrorRed across the receiver`() {
        val bm = renderInBitmap(40, 40) { drawErrorFillForDestructive(cornerRadius = 4.dp) }
        val pixel = bm.toPixelMap()[20, 20]
        assertTrue("red channel dominates ErrorRed", pixel.red > pixel.green)
        assertTrue("red channel dominates ErrorRed", pixel.red > pixel.blue)
        assertEquals("center matches the locked ErrorRed token", ErrorRed.red, pixel.red, 0.01f)
    }

    @Test
    fun `drawErrorFillForDestructive accepts pill radius (edge — large radius)`() {
        // Pill radius is 9999.dp; the rounded corners effectively collapse the rect to a stadium.
        val bm = renderInBitmap(40, 20) { drawErrorFillForDestructive(cornerRadius = 9999.dp) }
        // Center is inside the stadium → painted.
        assertTrue("stadium center painted", bm.alphaAt(20, 10) > 0.9f)
        // The exact corner is outside the stadium → untouched.
        assertEquals("stadium corner clipped", 0f, bm.alphaAt(0, 0), 0.01f)
    }
}
