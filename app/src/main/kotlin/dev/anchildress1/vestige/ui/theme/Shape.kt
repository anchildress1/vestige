package dev.anchildress1.vestige.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Radii per ADR-011 + poc/energy-tokens.jsx. Sharper than Mist — no pillows.
internal val RPill: Dp = 9999.dp
internal val RXL: Dp = 18.dp
internal val RL: Dp = 12.dp
internal val RM: Dp = 8.dp
internal val RS: Dp = 4.dp
internal val RXS: Dp = 2.dp

/**
 * Brand shape vocabulary — read everywhere via `VestigeTheme.shapes` using the same pattern as
 * `MaterialTheme.shapes`. `pill` is the stadium / status capsule (Mist's old `RPill`); `xs..xl`
 * are progressive corner radii sized to the Scoreboard cards and stat ribbons.
 */
@Immutable
data class VestigeShapes(
    val pill: RoundedCornerShape,
    val xl: RoundedCornerShape,
    val l: RoundedCornerShape,
    val m: RoundedCornerShape,
    val s: RoundedCornerShape,
    val xs: RoundedCornerShape,
)

internal val ScoreboardShapes: VestigeShapes = VestigeShapes(
    pill = RoundedCornerShape(RPill),
    xl = RoundedCornerShape(RXL),
    l = RoundedCornerShape(RL),
    m = RoundedCornerShape(RM),
    s = RoundedCornerShape(RS),
    xs = RoundedCornerShape(RXS),
)

internal val LocalVestigeShapes = staticCompositionLocalOf { ScoreboardShapes }

/** M3 bridge — Material components reach for `MaterialTheme.shapes.small/medium/large` directly. */
internal val M3Shapes: Shapes = Shapes(
    extraSmall = ScoreboardShapes.xs,
    small = ScoreboardShapes.s,
    medium = ScoreboardShapes.m,
    large = ScoreboardShapes.l,
    extraLarge = ScoreboardShapes.xl,
)
