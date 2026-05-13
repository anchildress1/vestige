package dev.anchildress1.vestige.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Dark only. glow / vapor / pulse are scoped accents applied via accent modifiers, never
// promoted to `primary` (Material would scatter them widely). `error` is the documented
// exception: M3 components reach for `colorScheme.error` directly (snackbars, text fields, alert
// dialogs), so the canonical destructive token has to live in the scheme to stay reachable
// without painting it manually at every call site.
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
