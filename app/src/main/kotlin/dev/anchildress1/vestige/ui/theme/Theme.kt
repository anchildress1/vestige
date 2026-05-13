package dev.anchildress1.vestige.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Dark only. Lime / Coral / Teal / Ember are scoped accents applied via accent modifiers, never
// promoted to `primary` (Material would scatter them widely). `error` is the documented
// exception: M3 components reach for `colorScheme.error` directly (snackbars, text fields, alert
// dialogs), so the destructive token has to live in the scheme to stay reachable without painting
// it manually at every call site. Coral and destructive share the heat semantic per ADR-011.
private val VestigeColorScheme = darkColorScheme(
    primary = Ink,
    onPrimary = Deep,
    secondary = Dim,
    onSecondary = Deep,
    background = Floor,
    onBackground = Ink,
    surface = S1,
    surfaceContainer = S2,
    surfaceContainerHighest = S3,
    onSurface = Ink,
    onSurfaceVariant = Dim,
    outline = Hair,
    error = ErrorRed,
    onError = Deep,
)

@Composable
fun VestigeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VestigeColorScheme,
        typography = VestigeTypography,
        content = content,
    )
}
