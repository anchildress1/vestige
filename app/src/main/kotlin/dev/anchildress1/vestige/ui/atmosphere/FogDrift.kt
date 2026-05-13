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
import dev.anchildress1.vestige.ui.theme.S2
import kotlin.math.cos
import kotlin.math.sin

/** Drift period A — `vesDrift1` mirrors poc/tokens.jsx `vesDrift1` keyframes. */
internal const val FOG_PERIOD_A_MS: Int = 22_000

/** Drift period B — `vesDrift2`. Alternating to keep the two blobs from beating in phase. */
internal const val FOG_PERIOD_B_MS: Int = 28_000

/**
 * Ambient fog drift per poc/design-review.md §2.4.
 *
 * Defaults to a single cool atmospheric tone — accent tints stay opt-in so this layer cannot
 * silently violate design-guidelines.md §"Color rules" ("`glow` and `vapor` … neither becomes
 * wallpaper"). Capture screen can pass `hueB = Vapor` during recording, Patterns can pass
 * `hueA = Glow`. Never both at once.
 */
@Composable
fun FogDrift(modifier: Modifier = Modifier, intensity: Float = 0.35f, hueA: Color = S2, hueB: Color = S2) {
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

    val alpha = intensity.coerceIn(0f, 1f)
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val r = maxOf(w, h) * 0.55f

        val centerA = fogCenter(phaseA, w, h, swing = 0.18f, offset = 0f)
        val centerB = fogCenter(phaseB, w, h, swing = 0.22f, offset = Math.PI.toFloat())

        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(hueA.copy(alpha = alpha), Color.Transparent),
                center = centerA,
                radius = r,
            ),
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(hueB.copy(alpha = alpha * 0.85f), Color.Transparent),
                center = centerB,
                radius = r * 0.9f,
            ),
        )
    }
}

internal fun fogCenter(phase: Float, w: Float, h: Float, swing: Float, offset: Float): Offset {
    val theta = phase * (2.0 * Math.PI).toFloat() + offset
    val dx = cos(theta) * (w * swing)
    val dy = sin(theta) * (h * swing)
    return Offset(x = w * 0.5f + dx, y = h * 0.5f + dy)
}
