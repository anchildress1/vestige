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
 * Compose translations of the `ves*` keyframe set in poc/design-review.md §2.5.
 *
 * Periodic animations are returned as [State]&lt;Float&gt; so callers can read them inside a
 * composable without recomposition churn. One-shot animations are exposed as [AnimationSpec]
 * presets so transitions reach for the same easing curves across screens.
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

    /** `vesSlide` — 320 ms slide, accelerate to decelerate, used for the [Sheet] entry. */
    val Slide: AnimationSpec<Float> = tween(durationMillis = 320, easing = FastOutSlowInEasing)

    /** Counterpart to [In] — 180 ms exit, accelerate-in. */
    val Out: AnimationSpec<Float> = tween(durationMillis = 180, easing = FastOutLinearInEasing)
}

/** Returns a 0..1 sawtooth driven by [periodMs]. Useful for halos that breathe with audio idle. */
@Composable
fun rememberVesPulse(periodMs: Int = VestigeMotion.PULSE_MS): State<Float> {
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

/** Slow ambient breathing — 0..1 reverse linear. */
@Composable
fun rememberVesBreath(periodMs: Int = VestigeMotion.BREATH_MS): State<Float> {
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

/** Shimmer sweep — 0..1 linear restart for gradient-x offsets on loading placeholders. */
@Composable
fun rememberVesShimmer(periodMs: Int = VestigeMotion.SHIMMER_MS): State<Float> {
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
