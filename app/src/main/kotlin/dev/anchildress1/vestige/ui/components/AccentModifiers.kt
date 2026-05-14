package dev.anchildress1.vestige.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/**
 * Lime left-rule for active patterns. Caller omits this modifier for snoozed / resolved / dismissed.
 * Scope: pattern cards in `state=active` only. ADR-011 §"Token additions" / §"What this breaks".
 */
@Composable
fun Modifier.limeLeftRuleForActive(width: Dp = RuleWidth, color: Color = VestigeTheme.colors.lime): Modifier =
    drawWithContent {
        drawLeftRule(width, color)
        drawContent()
    }

internal fun DrawScope.drawLeftRule(width: Dp, color: Color) {
    drawRect(
        color = color,
        topLeft = Offset.Zero,
        size = Size(width.toPx(), size.height),
    )
}

internal val RuleWidth: Dp = 3.dp

/**
 * Coral halo scaled by audio amplitude [level] (0..1). Drawn behind the record button while
 * the capture session is on-air. Idle (or NaN / negative) draws nothing.
 *
 * Replaces the Mist `vaporHaloOnRecording` halo. Halo color is coral because the REC button
 * carries the "heat" semantic during recording (per ADR-011). The AppTop status pill stays lime
 * — coral is reserved for REC button + destructive flows (ADR-011 Addendum 2026-05-14).
 */
@Composable
fun Modifier.coralHaloOnRecording(level: Float, color: Color = VestigeTheme.colors.coral): Modifier = drawWithContent {
    drawHalo(level, color)
    drawContent()
}

internal fun DrawScope.drawHalo(level: Float, color: Color) {
    val amp = if (level.isNaN()) 0f else level.coerceIn(0f, 1f)
    if (amp <= 0f) return
    val maxR = maxOf(size.width, size.height) * HALO_BASE_RADIUS
    val r = maxR * (HALO_MIN_SCALE + amp * HALO_AMP_SCALE)
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = HALO_ALPHA * amp), Color.Transparent),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = r,
        ),
    )
}

private const val HALO_BASE_RADIUS: Float = 0.6f
private const val HALO_MIN_SCALE: Float = 0.6f
private const val HALO_AMP_SCALE: Float = 0.8f
private const val HALO_ALPHA: Float = 0.45f

/**
 * GEMMA 4 · LOCAL ONLY status dot — lime when the model is ready. Halo stays small; status
 * indicator, not a brand accent. Coral is reserved for REC button heat, not this dot.
 */
@Composable
fun Modifier.limeDotForReady(diameter: Dp = StatusDotDiameter, color: Color = VestigeTheme.colors.lime): Modifier = this
    .size(diameter)
    .drawBehind { drawStatusDot(color) }

internal fun DrawScope.drawStatusDot(color: Color) {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = DOT_HALO_ALPHA), Color.Transparent),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = maxOf(size.width, size.height),
        ),
    )
    drawCircle(color = color, radius = size.minDimension / 2f)
    drawCircle(
        color = color.copy(alpha = DOT_RIM_ALPHA),
        radius = size.minDimension / 2f,
        style = Stroke(width = DotRimWidth.toPx()),
    )
}

internal val StatusDotDiameter: Dp = 8.dp
private val DotRimWidth: Dp = 1.dp
private const val DOT_HALO_ALPHA: Float = 0.25f
private const val DOT_RIM_ALPHA: Float = 0.7f

/**
 * Destructive fill — wipe confirmations only. Defaults to the destructive token in
 * `VestigeTheme.colors.errorRed` (aliased to coral per ADR-011 — destructive and heat share the
 * same atom). A destructive control can never wear brand styling.
 */
@Composable
fun Modifier.errorFillForDestructive(
    cornerRadius: Dp = DestructivePillRadius,
    color: Color = VestigeTheme.colors.errorRed,
): Modifier = this
    .clip(RoundedCornerShape(cornerRadius))
    .drawWithContent {
        drawDestructive(cornerRadius, color)
        drawContent()
    }

internal fun DrawScope.drawDestructive(cornerRadius: Dp, color: Color) {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx()),
    )
}

/** Stadium pill radius — matches `VestigeTheme.shapes.pill` corner. */
internal val DestructivePillRadius: Dp = 9999.dp
