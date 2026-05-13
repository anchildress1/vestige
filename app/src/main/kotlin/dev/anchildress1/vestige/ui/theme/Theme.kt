package dev.anchildress1.vestige.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Dark only. The scheme does not branch on isSystemInDarkTheme() per design-guidelines.md.
// glow / vapor / pulse / error stay out of the M3 colorScheme — they are scoped accents,
// applied via accent modifiers, not promoted to "primary" which Material would scatter widely.
private val VestigeColorScheme = darkColorScheme(
    primary = Ink,
    onPrimary = Deep,
    secondary = Mist,
    onSecondary = Deep,
    background = Bg,
    onBackground = Ink,
    surface = S1,
    surfaceContainer = S2,
    surfaceContainerHighest = S3,
    onSurface = Ink,
    onSurfaceVariant = Mist,
    outline = S3,
    error = ErrorRed,
    onError = Ink,
)

@Composable
fun VestigeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VestigeColorScheme,
        typography = VestigeTypography,
        content = content,
    )
}
