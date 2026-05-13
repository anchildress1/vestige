package dev.anchildress1.vestige.ui.motion

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State

/**
 * Compose port of the `ves*` keyframe names from poc/tokens.jsx.
 *
 * Period defaults are the port's choices — JSX names carry behavior, not durations.
 */
object VestigeMotion {

    /** `vesPulse` — soft 1.4 s breathing on idle accents (`LOCAL · READY` dot, idle stone). */
    const val PULSE_MS: Int = 1_400

    /** `vesBreath` — slower 6 s ambient breathing on hero surfaces. */
    const val BREATH_MS: Int = 6_000

    /** `vesShimmer` — 1.8 s gradient sweep for loading placeholders. */
    const val SHIMMER_MS: Int = 1_800

    /** `vesSpin` — 16 s conic moonstone ring rotation under `MistHero`. */
    const val SPIN_MS: Int = 16_000

    /** `vesIn` — 200 ms enter, decelerate. */
    val In: AnimationSpec<Float> = tween(durationMillis = 200, easing = LinearOutSlowInEasing)

    /** `vesFade` — 240 ms fade, standard curve. */
    val Fade: AnimationSpec<Float> = tween(durationMillis = 240, easing = FastOutSlowInEasing)

    /** `vesSlide` — 320 ms slide, used for sheet entry per poc/design-review.md §3.1. */
    val Slide: AnimationSpec<Float> = tween(durationMillis = 320, easing = FastOutSlowInEasing)

    /** Counterpart to [In] — 180 ms exit, accelerate-in. */
    val Out: AnimationSpec<Float> = tween(durationMillis = 180, easing = FastOutLinearInEasing)
}

/** 0..1 triangle wave at [periodMs]. For halos that breathe with audio idle. */
@Composable
fun rememberVesPulse(periodMs: Int = VestigeMotion.PULSE_MS): State<Float> {
    require(periodMs > 0) { "periodMs must be positive (was $periodMs)" }
    val transition = rememberInfiniteTransition(label = "vesPulse")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "vesPulseFraction",
    )
}

/** 0..1 triangle wave for hero-surface ambient breathing. */
@Composable
fun rememberVesBreath(periodMs: Int = VestigeMotion.BREATH_MS): State<Float> {
    require(periodMs > 0) { "periodMs must be positive (was $periodMs)" }
    val transition = rememberInfiniteTransition(label = "vesBreath")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "vesBreathFraction",
    )
}

/** 0..1 sawtooth — restart-mode sweep for loading-placeholder gradient-x offsets. */
@Composable
fun rememberVesShimmer(periodMs: Int = VestigeMotion.SHIMMER_MS): State<Float> {
    require(periodMs > 0) { "periodMs must be positive (was $periodMs)" }
    val transition = rememberInfiniteTransition(label = "vesShimmer")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "vesShimmerFraction",
    )
}

/** Continuous rotation — 0..360 degrees for the `MistHero` conic ring. */
@Composable
fun rememberVesSpin(periodMs: Int = VestigeMotion.SPIN_MS): State<Float> {
    require(periodMs > 0) { "periodMs must be positive (was $periodMs)" }
    val transition = rememberInfiniteTransition(label = "vesSpin")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = SPIN_FULL_ROTATION,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "vesSpinDegrees",
    )
}

private const val SPIN_FULL_ROTATION: Float = 360f
