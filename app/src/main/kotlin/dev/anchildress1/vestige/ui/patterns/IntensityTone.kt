package dev.anchildress1.vestige.ui.patterns

import androidx.compose.runtime.Composable
import dev.anchildress1.vestige.model.PatternState
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/**
 * Shared lifecycle-tone vocabulary for the pattern list + detail trace bars. Kept here so the
 * list screen can resolve a tone without importing types from the detail screen (the prior
 * arrangement crossed file boundaries through `IntensityTone` defined on detail).
 *
 * `peak` is the only carrier on the enum itself — the concrete color binding lives in
 * [themedStyle], the single composable resolver. One source of truth: tone → color resolves
 * through `VestigeTheme.colors` in exactly one place.
 */
internal enum class IntensityTone(val peak: Boolean) {
    ACTIVE_PEAK(peak = true),
    SNOOZED(peak = false),
    SETTLED(peak = false),
    FROZEN(peak = false),
}

internal fun intensityToneFor(state: PatternState): IntensityTone = when (state) {
    PatternState.ACTIVE -> IntensityTone.ACTIVE_PEAK
    PatternState.SNOOZED -> IntensityTone.SNOOZED
    PatternState.CLOSED, PatternState.DROPPED -> IntensityTone.SETTLED
    PatternState.BELOW_THRESHOLD -> IntensityTone.FROZEN
}

internal fun cardSectionToneFor(section: PatternSection): IntensityTone = when (section) {
    PatternSection.ACTIVE -> IntensityTone.ACTIVE_PEAK
    PatternSection.SKIPPED -> IntensityTone.SNOOZED
    PatternSection.CLOSED, PatternSection.DROPPED -> IntensityTone.SETTLED
}

@Composable
internal fun IntensityTone.themedStyle(): PatternIntensityStyle {
    val colors = VestigeTheme.colors
    val accent = when (this) {
        IntensityTone.ACTIVE_PEAK -> colors.lime
        IntensityTone.SNOOZED -> colors.ember
        IntensityTone.SETTLED -> colors.teal
        IntensityTone.FROZEN -> colors.tealDim
    }
    return PatternIntensityStyle(accent = accent, peak = peak)
}
