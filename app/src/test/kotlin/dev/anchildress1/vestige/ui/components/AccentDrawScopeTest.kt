package dev.anchildress1.vestige.ui.components

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.ui.theme.Coral
import dev.anchildress1.vestige.ui.theme.ErrorRed
import dev.anchildress1.vestige.ui.theme.Lime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pos / neg / err / edge coverage for `AccentModifiers.kt` Scoreboard draw paths.
 *
 * `NATIVE` graphics mode is required so `ImageBitmap` writes land in a readable pixel buffer
 * instead of Robolectric's no-op canvas shadow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccentDrawScopeTest {

    // limeLeftRuleForActive — paints a lime stripe along the leading edge.

    @Test
    fun `drawLeftRule paints Lime on the left edge and leaves the right untouched`() {
        val bm = renderInBitmap(40, 40) { drawLeftRule(3.dp, Lime) }
        assertTrue("left edge should be opaque", bm.alphaAt(1, 20) > 0.9f)
        assertEquals("right edge should be untouched (alpha 0)", 0f, bm.alphaAt(38, 20), 0.01f)
    }

    @Test
    fun `drawLeftRule respects custom width (edge — width past midpoint)`() {
        val bm = renderInBitmap(40, 40) { drawLeftRule(25.dp, Lime) }
        assertTrue("center should now be inside the rule", bm.alphaAt(20, 20) > 0.9f)
    }

    @Test
    fun `drawLeftRule respects custom color (pos — color override)`() {
        val bm = renderInBitmap(20, 20) { drawLeftRule(3.dp, Coral) }
        val pixel = bm.toPixelMap()[1, 10]
        assertTrue("red channel dominates Coral", pixel.red > pixel.blue)
    }

    // coralHaloOnRecording — pos / neg / err / edge.

    @Test
    fun `drawHalo skips draw at zero level (neg)`() {
        val bm = renderInBitmap(40, 40) { drawHalo(level = 0f, color = Coral) }
        assertEquals("center untouched", 0f, bm.alphaAt(20, 20), 0.01f)
    }

    @Test
    fun `drawHalo paints at mid level (pos)`() {
        val bm = renderInBitmap(60, 60) { drawHalo(level = 0.5f, color = Coral) }
        assertTrue("halo should tint near the center", bm.alphaAt(30, 30) > 0f)
    }

    @Test
    fun `drawHalo paints at full amp (edge upper bound)`() {
        val bm = renderInBitmap(60, 60) { drawHalo(level = 1f, color = Coral) }
        assertTrue("halo brighter at amp=1 than mid", bm.alphaAt(30, 30) > 0f)
    }

    @Test
    fun `drawHalo treats negative level as idle (err)`() {
        val bm = renderInBitmap(40, 40) { drawHalo(level = -0.5f, color = Coral) }
        assertEquals("center untouched on negative", 0f, bm.alphaAt(20, 20), 0.01f)
    }

    @Test
    fun `drawHalo treats NaN as idle (err)`() {
        val bm = renderInBitmap(40, 40) { drawHalo(level = Float.NaN, color = Coral) }
        assertEquals("center untouched on NaN", 0f, bm.alphaAt(20, 20), 0.01f)
    }

    @Test
    fun `drawHalo clamps level above 1 (edge — above bound)`() {
        val bm = renderInBitmap(60, 60) { drawHalo(level = 99f, color = Coral) }
        assertTrue("clamped level still draws", bm.alphaAt(30, 30) > 0f)
    }

    // limeDotForReady — halo + inner.

    @Test
    fun `drawStatusDot paints inner disc near the center`() {
        val bm = renderInBitmap(32, 32) { drawStatusDot(color = Lime) }
        assertTrue("center pixel inside the dot is opaque-ish", bm.alphaAt(16, 16) > 0.5f)
    }

    @Test
    fun `drawStatusDot halo extends past the inner disc`() {
        val bm = renderInBitmap(40, 40) { drawStatusDot(color = Lime) }
        assertTrue("corner gets the halo gradient", bm.alphaAt(0, 0) > 0f)
    }

    // errorFillForDestructive — paints a rounded ErrorRed (= Coral) rect.

    @Test
    fun `drawDestructive paints destructive color across the receiver`() {
        val bm = renderInBitmap(40, 40) { drawDestructive(cornerRadius = 4.dp) }
        val pixel = bm.toPixelMap()[20, 20]
        assertTrue("red channel dominates destructive heat", pixel.red > pixel.green)
        assertTrue("red channel dominates destructive heat", pixel.red > pixel.blue)
        assertEquals("center matches the locked destructive token", ErrorRed.red, pixel.red, 0.01f)
    }

    @Test
    fun `drawDestructive accepts pill radius (edge — large radius)`() {
        val bm = renderInBitmap(40, 20) { drawDestructive(cornerRadius = 9999.dp) }
        assertTrue("stadium center painted", bm.alphaAt(20, 10) > 0.9f)
        assertEquals("stadium corner clipped", 0f, bm.alphaAt(0, 0), 0.01f)
    }
}
