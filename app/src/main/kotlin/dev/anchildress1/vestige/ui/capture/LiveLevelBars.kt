package dev.anchildress1.vestige.ui.capture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/**
 * Coral live-level bar strip — paints [levels] left-to-right as scaled-height rectangles. Bars
 * brighten as the value rises; bars above [GLOW_THRESHOLD] pick up a soft halo. The composable
 * is decorative — semantics are cleared so TalkBack doesn't read a meaningless region. The
 * 42-bar default matches the recording mockup; callers driving fewer values can shrink the list.
 *
 * Canvas-based instead of `Row { Box }` because at 25 Hz we redraw 42 nodes 1500× per minute;
 * skipping a heavier layout pass is the right cost trade.
 */
@Composable
fun LiveLevelBars(levels: List<Float>, modifier: Modifier = Modifier, height: Dp = DEFAULT_HEIGHT) {
    val coral = VestigeTheme.colors.coral
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics { },
    ) {
        drawBars(levels, coral)
    }
}

internal fun DrawScope.drawBars(levels: List<Float>, color: androidx.compose.ui.graphics.Color) {
    if (levels.isEmpty()) return
    val total = size.width
    val barCount = levels.size
    val barWidth = (total / barCount).coerceAtLeast(MIN_BAR_WIDTH_PX)
    val gapWidth = (barWidth * GAP_RATIO).coerceAtMost(barWidth * 0.5f)
    val drawWidth = barWidth - gapWidth
    for (i in 0 until barCount) {
        val level = levels[i].coerceIn(0f, 1f)
        val barHeight = (size.height * level).coerceAtLeast(MIN_BAR_HEIGHT_PX)
        val left = i * barWidth
        val top = size.height - barHeight
        val alpha = MIN_ALPHA + level * (1f - MIN_ALPHA)
        if (level > GLOW_THRESHOLD) {
            drawRect(
                color = color.copy(alpha = GLOW_ALPHA),
                topLeft = Offset(left - GLOW_HALO_PX, top - GLOW_HALO_PX),
                size = Size(drawWidth + GLOW_HALO_PX * 2f, barHeight + GLOW_HALO_PX * 2f),
            )
        }
        drawRect(
            color = color.copy(alpha = alpha),
            topLeft = Offset(left, top),
            size = Size(drawWidth, barHeight),
        )
    }
}

private val DEFAULT_HEIGHT: Dp = 80.dp
private const val MIN_BAR_WIDTH_PX: Float = 2f
private const val MIN_BAR_HEIGHT_PX: Float = 4f
private const val GAP_RATIO: Float = 0.25f
private const val MIN_ALPHA: Float = 0.30f
private const val GLOW_THRESHOLD: Float = 0.65f
private const val GLOW_ALPHA: Float = 0.35f
private const val GLOW_HALO_PX: Float = 2f
