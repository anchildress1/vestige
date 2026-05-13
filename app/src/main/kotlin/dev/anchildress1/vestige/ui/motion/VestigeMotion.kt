package dev.anchildress1.vestige.ui.motion

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State

/**
 * Compose port of the `sb*` keyframe names from `poc/energy-tokens.jsx`.
 *
 * Period defaults are the port's choices — JSX names carry behavior, not durations.
 */
object VestigeMotion {

    /** `sbPulse` — 1.4 s opacity breathing for on-air / status dot. */
    const val PULSE_MS: Int = 1_400

    /** `sbBlink` — hard 0/1 cutover at 50 %, used by status indicators that should not "breathe". */
    const val BLINK_MS: Int = 800

    /** `sbScroll` — translateX(0 → −50%) for ticker rows; uses linear-restart. */
    const val SCROLL_MS: Int = 18_000

    /** `sbTick` — single-step translateY at 90 → 95 → 100 %, restart-mode, fires per stat update. */
    const val TICK_MS: Int = 320

    /** `sbBars` — 1.0 s reverse triangle for audio-meter bar scaleY. */
    const val BARS_MS: Int = 1_000

    /** `sbSweep` — 1.8 s linear-restart for the loading-placeholder gradient sweep. */
    const val SWEEP_MS: Int = 1_800

    /** `sbWobble` — 2.6 s reverse triangle for the Roast stamp's −1.5° ↔ 1.5° wobble. */
    const val WOBBLE_MS: Int = 2_600

    /** `sbRise` — 220 ms enter, decelerate. */
    val Rise: AnimationSpec<Float> = tween(durationMillis = 220, easing = LinearOutSlowInEasing)

    /** Counterpart to [Rise] — 180 ms exit, accelerate-in. */
    val Out: AnimationSpec<Float> = tween(durationMillis = 180, easing = FastOutLinearInEasing)

    /** Fade — 240 ms standard curve. */
    val Fade: AnimationSpec<Float> = tween(durationMillis = 240, easing = FastOutSlowInEasing)

    /** Sheet slide — 320 ms standard curve. */
    val Slide: AnimationSpec<Float> = tween(durationMillis = 320, easing = FastOutSlowInEasing)
}

/** 0..1 triangle wave at [periodMs]. On-air dot pulse + record-button breathing. */
@Composable
fun rememberSbPulse(periodMs: Int = VestigeMotion.PULSE_MS): State<Float> {
    require(periodMs > 0) { "periodMs must be positive (was $periodMs)" }
    val transition = rememberInfiniteTransition(label = "sbPulse")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sbPulseFraction",
    )
}

/** Hard 0 ↔ 1 step at the half-period boundary. Status indicator only — never on body content. */
@Composable
fun rememberSbBlink(periodMs: Int = VestigeMotion.BLINK_MS): State<Float> {
    require(periodMs > 0) { "periodMs must be positive (was $periodMs)" }
    val halfPeriodMs = periodMs / 2
    val transition = rememberInfiniteTransition(label = "sbBlink")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = periodMs
                0f at 0
                0f at halfPeriodMs
                1f at (halfPeriodMs + 1).coerceAtMost(periodMs)
                1f at periodMs
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "sbBlinkFraction",
    )
}

/** 0..1 reverse triangle for audio-meter bar height. Idle bars sit at scaleY ≈ 0.3. */
@Composable
fun rememberSbBars(periodMs: Int = VestigeMotion.BARS_MS): State<Float> {
    require(periodMs > 0) { "periodMs must be positive (was $periodMs)" }
    val transition = rememberInfiniteTransition(label = "sbBars")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sbBarsFraction",
    )
}

/** 0..1 sawtooth — restart-mode sweep for loading-placeholder gradient-x offsets. */
@Composable
fun rememberSbSweep(periodMs: Int = VestigeMotion.SWEEP_MS): State<Float> {
    require(periodMs > 0) { "periodMs must be positive (was $periodMs)" }
    val transition = rememberInfiniteTransition(label = "sbSweep")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sbSweepFraction",
    )
}

/** −1..1 reverse triangle — Roast stamp wobble between −1.5° and +1.5°. */
@Composable
fun rememberSbWobble(periodMs: Int = VestigeMotion.WOBBLE_MS): State<Float> {
    require(periodMs > 0) { "periodMs must be positive (was $periodMs)" }
    val transition = rememberInfiniteTransition(label = "sbWobble")
    return transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sbWobbleFraction",
    )
}
