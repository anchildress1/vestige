package dev.anchildress1.vestige.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.ui.theme.ErrorRed
import dev.anchildress1.vestige.ui.theme.Glow
import dev.anchildress1.vestige.ui.theme.Pulse
import dev.anchildress1.vestige.ui.theme.RadiusTokens
import dev.anchildress1.vestige.ui.theme.Vapor

/**
 * Glow left-rule per design-guidelines.md §"Where each accent lives" — active patterns only.
 *
 * Paints a 3 dp [Glow] stripe down the leading edge of the receiver. The rule is the surface
 * signal that a pattern is `state=active`; snoozed / resolved / dismissed cards drop the rule
 * (caller chooses not to apply this modifier).
 */
fun Modifier.glowLeftRule(width: Dp = GlowRuleWidth, color: Color = Glow): Modifier = drawWithContent {
    drawContent()
    drawRect(
        color = color,
        topLeft = Offset.Zero,
        size = Size(width.toPx(), size.height),
    )
}

private val GlowRuleWidth: Dp = 3.dp

/**
 * Vapor halo for the recording state of `MistHero` per design-guidelines.md §"vapor".
 *
 * Scales a radial-gradient halo by [level] (0..1 audio amplitude). Idle ([level] == 0) draws no
 * halo so the stone stays calm; recording (level > 0) shows an animated [Vapor] glow that the
 * caller drives off the live `AudioMeter` signal.
 */
fun Modifier.vaporHaloOnRecording(level: Float, color: Color = Vapor): Modifier = drawBehind {
    val amp = level.coerceIn(0f, 1f)
    if (amp <= 0f) return@drawBehind
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
 * Pulse ready-dot per design-guidelines.md §"pulse" — `LOCAL · READY` only.
 *
 * Sized 8 dp by default with a soft surrounding halo at low opacity. The halo intentionally
 * stays small so the dot doesn't read as an accent surface; it's a status indicator, not a brand
 * moment.
 */
fun Modifier.pulseDotForReady(diameter: Dp = PulseDotDiameter, color: Color = Pulse): Modifier = this
    .size(diameter)
    .drawBehind {
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

private val PulseDotDiameter: Dp = 8.dp
private val PulseRimWidth: Dp = 1.dp
private const val PULSE_HALO_ALPHA: Float = 0.25f
private const val PULSE_RIM_ALPHA: Float = 0.7f

/**
 * Destructive fill — wipe confirmations only per design-guidelines.md §"error".
 *
 * Lock at the call-site so destructive surfaces always use [ErrorRed] and "ink in a red box"
 * disguised as brand styling stops being a way to lose data.
 */
fun Modifier.errorFillForDestructive(): Modifier = this
    .clip(RadiusTokens.Pill)
    .background(ErrorRed, RadiusTokens.Pill)
