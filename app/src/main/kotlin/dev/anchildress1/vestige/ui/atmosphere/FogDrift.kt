package dev.anchildress1.vestige.ui.atmosphere

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import dev.anchildress1.vestige.ui.theme.Vapor
import kotlin.math.cos
import kotlin.math.sin

/** Drift period A — `vesDrift1` mirrors poc/tokens.jsx `vesDrift1` keyframes. */
internal const val FOG_PERIOD_A_MS: Int = 22_000

/** Drift period B — `vesDrift2`. Alternating to keep the two blobs from beating in phase. */
internal const val FOG_PERIOD_B_MS: Int = 28_000

private const val FOG_RADIUS_FRACTION: Float = 0.55f
private const val FOG_BLOB_A_SWING: Float = 0.18f
private const val FOG_BLOB_B_SWING: Float = 0.22f
private const val FOG_BLOB_B_RADIUS_FACTOR: Float = 0.9f
private const val FOG_BLOB_B_ALPHA_FACTOR: Float = 0.85f

/**
 * Ambient fog drift per poc/design-review.md §2.4.
 *
 * Defaults to [Vapor] on both blobs to match the bluish atmospheric undertone in
 * `poc/screenshots/capture.png` / `patterns.png`. The §"Color rules" "no wallpaper" caveat is
 * about solid accent paint; low-opacity ambient fog is the layer §2.4 explicitly authorizes.
 * Screens that want a purple undertone (Roast sheet, pattern detail hero) override `hueA = Glow`.
 */
@Composable
fun FogDrift(modifier: Modifier = Modifier, intensity: Float = 0.35f, hueA: Color = Vapor, hueB: Color = Vapor) {
    val transition = rememberInfiniteTransition(label = "fogDrift")
    val phaseA by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = FOG_PERIOD_A_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "fogDriftA",
    )
    val phaseB by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = FOG_PERIOD_B_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "fogDriftB",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        drawFogDrift(intensity = intensity, hueA = hueA, hueB = hueB, phaseA = phaseA, phaseB = phaseB)
    }
}

internal fun DrawScope.drawFogDrift(intensity: Float, hueA: Color, hueB: Color, phaseA: Float, phaseB: Float) {
    val alpha = intensity.coerceIn(0f, 1f)
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val r = maxOf(w, h) * FOG_RADIUS_FRACTION

    val centerA = fogCenter(phaseA, w, h, swing = FOG_BLOB_A_SWING, offset = 0f)
    val centerB = fogCenter(phaseB, w, h, swing = FOG_BLOB_B_SWING, offset = Math.PI.toFloat())

    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(hueA.copy(alpha = alpha), Color.Transparent),
            center = centerA,
            radius = r,
        ),
    )
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(hueB.copy(alpha = alpha * FOG_BLOB_B_ALPHA_FACTOR), Color.Transparent),
            center = centerB,
            radius = r * FOG_BLOB_B_RADIUS_FACTOR,
        ),
    )
}

internal fun fogCenter(phase: Float, w: Float, h: Float, swing: Float, offset: Float): Offset {
    val theta = phase * (2.0 * Math.PI).toFloat() + offset
    val dx = cos(theta) * (w * swing)
    val dy = sin(theta) * (h * swing)
    return Offset(x = w * 0.5f + dx, y = h * 0.5f + dy)
}
