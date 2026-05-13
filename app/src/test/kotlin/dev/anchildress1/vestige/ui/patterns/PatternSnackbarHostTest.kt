package dev.anchildress1.vestige.ui.patterns

import dev.anchildress1.vestige.ui.theme.ScoreboardColors
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Snackbar foreground colors flow from `VestigeColorScheme.inverseSurface` (= S2) and
 * `inversePrimary` (= Ink) — see Theme.kt. This locks the contrast contract: the action label
 * (`inversePrimary` = Ink) must clear AA contrast against the container (`inverseSurface` = S2)
 * so the "Undo" affordance stays legible without per-call-site overrides.
 */
class PatternSnackbarHostTest {

    @Test
    fun `snackbar action color clears AA contrast on the inverse surface`() {
        val foreground = ScoreboardColors.ink
        val background = ScoreboardColors.s2
        assertTrue(contrastRatio(foreground, background) >= 4.5f)
    }
}

private fun contrastRatio(
    foreground: androidx.compose.ui.graphics.Color,
    background: androidx.compose.ui.graphics.Color,
): Float {
    val lighter = maxOf(relativeLuminance(foreground), relativeLuminance(background))
    val darker = minOf(relativeLuminance(foreground), relativeLuminance(background))
    return (lighter + 0.05f) / (darker + 0.05f)
}

private fun relativeLuminance(color: androidx.compose.ui.graphics.Color): Float {
    fun linear(channel: Float): Float = if (channel <= 0.03928f) {
        channel / 12.92f
    } else {
        ((channel + 0.055f) / 1.055f).pow(2.4f)
    }

    return 0.2126f * linear(color.red) + 0.7152f * linear(color.green) + 0.0722f * linear(color.blue)
}
