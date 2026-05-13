package dev.anchildress1.vestige.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.ui.theme.ErrorRed
import dev.anchildress1.vestige.ui.theme.Glow
import dev.anchildress1.vestige.ui.theme.Pulse
import dev.anchildress1.vestige.ui.theme.RadiusTokens
import dev.anchildress1.vestige.ui.theme.Vapor

/**
 * Glow left-rule for active patterns. Caller omits this modifier for snoozed / resolved / dismissed.
 * Spec: design-guidelines.md §"Where each accent lives".
 */
fun Modifier.glowLeftRule(width: Dp = GlowRuleWidth, color: Color = Glow): Modifier = drawWithContent {
    drawGlowLeftRule(width, color)
    drawContent()
}

internal fun DrawScope.drawGlowLeftRule(width: Dp, color: Color) {
    drawRect(
        color = color,
        topLeft = Offset.Zero,
        size = Size(width.toPx(), size.height),
    )
}

internal val GlowRuleWidth: Dp = 3.dp

/**
 * Vapor halo scaled by audio amplitude [level] (0..1).
 *
 * Idle (or NaN / negative) draws nothing. Use this through `VestigeSurface(accentModifier = …)`
 * so it sits between the glass fill and foreground content, or on a larger wrapper if the halo
 * needs to spill beyond the stone bounds. Spec: design-guidelines.md §"Where each accent lives"
 * + poc/design-review.md §3.3.
 */
fun Modifier.vaporHaloOnRecording(level: Float, color: Color = Vapor): Modifier = drawWithContent {
    drawVaporHaloOnRecording(level, color)
    drawContent()
}

internal fun DrawScope.drawVaporHaloOnRecording(level: Float, color: Color) {
    val amp = if (level.isNaN()) 0f else level.coerceIn(0f, 1f)
    if (amp <= 0f) return
    val maxR = maxOf(size.width, size.height) * VAPOR_HALO_BASE_RADIUS
    val r = maxR * (VAPOR_HALO_MIN_SCALE + amp * VAPOR_HALO_AMP_SCALE)
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = VAPOR_HALO_ALPHA * amp), Color.Transparent),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = r,
        ),
    )
}

private const val VAPOR_HALO_BASE_RADIUS: Float = 0.6f
private const val VAPOR_HALO_MIN_SCALE: Float = 0.6f
private const val VAPOR_HALO_AMP_SCALE: Float = 0.8f
private const val VAPOR_HALO_ALPHA: Float = 0.45f

/**
 * LOCAL · READY status dot. Halo stays small — status indicator, not a brand accent.
 * Spec: design-guidelines.md §"Where each accent lives".
 */
fun Modifier.pulseDotForReady(diameter: Dp = PulseDotDiameter, color: Color = Pulse): Modifier = this
    .size(diameter)
    .drawBehind { drawPulseDotForReady(color) }

internal fun DrawScope.drawPulseDotForReady(color: Color) {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = PULSE_HALO_ALPHA), Color.Transparent),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = maxOf(size.width, size.height),
        ),
    )
    drawCircle(color = color, radius = size.minDimension / 2f)
    drawCircle(
        color = color.copy(alpha = PULSE_RIM_ALPHA),
        radius = size.minDimension / 2f,
        style = Stroke(width = PulseRimWidth.toPx()),
    )
}

internal val PulseDotDiameter: Dp = 8.dp
private val PulseRimWidth: Dp = 1.dp
private const val PULSE_HALO_ALPHA: Float = 0.25f
private const val PULSE_RIM_ALPHA: Float = 0.7f

/**
 * Destructive fill — wipe confirmations only. Use this through `VestigeSurface(accentModifier = …)`
 * so it replaces the glass fill without tinting foreground content. Locks call-sites to [ErrorRed]
 * so a destructive control can never wear brand styling. Spec: design-guidelines.md §"Where each
 * accent lives".
 */
fun Modifier.errorFillForDestructive(cornerRadius: Dp = RadiusTokens.RPill): Modifier = this
    .clip(RoundedCornerShape(cornerRadius))
    .drawWithContent {
        drawErrorFillForDestructive(cornerRadius)
        drawContent()
    }

internal fun DrawScope.drawErrorFillForDestructive(cornerRadius: Dp) {
    drawRoundRect(
        color = ErrorRed,
        cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx()),
    )
}
