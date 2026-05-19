package dev.anchildress1.vestige.ui.capture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import kotlin.math.pow

/**
 * Lime live-level bar strip — paints [levels] left-to-right as scaled-height rectangles. Raw RMS
 * is run through [perceptualBarLevel] so quiet speech is clearly visible while loud still maxes
 * out; bars brighten with the perceptual value and pick up a halo past [GLOW_THRESHOLD]. The
 * composable is decorative — semantics are cleared so TalkBack doesn't read a meaningless region.
 *
 * Canvas-based instead of `Row { Box }` because at 25 Hz we redraw 42 nodes 1500× per minute;
 * skipping a heavier layout pass is the right cost trade.
 */
@Composable
fun LiveLevelBars(levels: List<Float>, modifier: Modifier = Modifier, height: Dp = DEFAULT_HEIGHT) {
    val lime = VestigeTheme.colors.lime
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics { },
    ) {
        drawBars(levels, lime)
    }
}

/**
 * Maps raw RMS `[0,1]` to a perceptual bar level. True silence stays flat; any audible input
 * jumps to a clearly visible floor, then a sub-1 exponent expands the quiet range so normal
 * speech fills most of the strip and only shouting saturates.
 */
internal fun perceptualBarLevel(raw: Float): Float {
    if (raw <= SILENCE_EPSILON) return 0f
    val shaped = raw.coerceIn(0f, 1f).pow(PERCEPTUAL_EXP)
    return (shaped * PERCEPTUAL_GAIN).coerceIn(MIN_VISIBLE, 1f)
}

internal fun DrawScope.drawBars(levels: List<Float>, color: Color) {
    if (levels.isEmpty()) return
    val total = size.width
    val barCount = levels.size
    val barWidth = (total / barCount).coerceAtLeast(MIN_BAR_WIDTH_PX)
    val gapWidth = (barWidth * GAP_RATIO).coerceAtMost(barWidth * 0.5f)
    val drawWidth = barWidth - gapWidth
    for (i in 0 until barCount) {
        val level = perceptualBarLevel(levels[i])
        val barHeight = (size.height * level).coerceAtLeast(MIN_BAR_HEIGHT_PX)
        val left = i * barWidth
        val top = size.height - barHeight
        // Solid lime — no per-bar alpha. The old alpha-by-level made quiet bars translucent,
        // which composited over the floor as a duller green than the rest of the app.
        if (level > GLOW_THRESHOLD) {
            drawRect(
                color = color.copy(alpha = GLOW_ALPHA),
                topLeft = Offset(left - GLOW_HALO_PX, top - GLOW_HALO_PX),
                size = Size(drawWidth + GLOW_HALO_PX * 2f, barHeight + GLOW_HALO_PX * 2f),
            )
        }
        drawRect(
            color = color,
            topLeft = Offset(left, top),
            size = Size(drawWidth, barHeight),
        )
    }
}

private val DEFAULT_HEIGHT: Dp = 80.dp
private const val MIN_BAR_WIDTH_PX: Float = 2f
private const val MIN_BAR_HEIGHT_PX: Float = 4f
private const val GAP_RATIO: Float = 0.25f
private const val GLOW_THRESHOLD: Float = 0.65f
private const val GLOW_ALPHA: Float = 0.35f
private const val GLOW_HALO_PX: Float = 2f

// Perceptual mapping: anything quieter than this reads as silence; everything louder snaps to
// at least MIN_VISIBLE then expands via the sub-1 exponent so quiet speech moves the strip.
private const val SILENCE_EPSILON: Float = 0.002f
private const val PERCEPTUAL_EXP: Float = 0.45f
private const val PERCEPTUAL_GAIN: Float = 1.15f
private const val MIN_VISIBLE: Float = 0.18f
