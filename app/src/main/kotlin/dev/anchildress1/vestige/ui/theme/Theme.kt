package dev.anchildress1.vestige.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

// Dark only. Lime / Coral / Teal / Ember are scoped accents applied via accent modifiers, never
// promoted to `primary` (Material would scatter them widely). `error` is the documented
// exception: M3 components reach for `colorScheme.error` directly (snackbars, text fields, alert
// dialogs), so the destructive token has to live in the scheme to stay reachable without painting
// it manually at every call site. Coral and destructive share the heat semantic per ADR-011.
// Inverse slots are filled so M3 Snackbar / SwitchTrack / TopAppBar inverse states resolve from
// the theme — never from per-call-site overrides. PatternSnackbarHost previously hardcoded
// container + content + action colors because the unfilled defaults collapsed to near-zero
// contrast against Ink-as-primary; pinning them here keeps the rescue on the theme.
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
    inverseSurface = S2,
    inverseOnSurface = Ink,
    inversePrimary = Ink,
)

/**
 * One-stop accessor for Vestige's brand vocabulary — same shape as `MaterialTheme`. Production
 * call sites read every color / text style / shape through this object; raw tokens in `Color.kt`,
 * `Type.kt`, and `Shape.kt` are the canonical bank but production code routes through the
 * `CompositionLocal`s so future theme variants (e.g., Hardass persona overrides) can swap a
 * single slot without ripple edits.
 */
object VestigeTheme {
    val colors: VestigeColors
        @Composable @ReadOnlyComposable
        get() = LocalVestigeColors.current

    val typography: VestigeTypography
        @Composable @ReadOnlyComposable
        get() = LocalVestigeTypography.current

    val shapes: VestigeShapes
        @Composable @ReadOnlyComposable
        get() = LocalVestigeShapes.current
}

@Composable
fun VestigeTheme(content: @Composable () -> Unit) {
    // LocalContentColor is provided inside MaterialTheme so every descendant — whether or not it
    // routes through M3 Surface / Scaffold — inherits Ink as the default Text color. Without this
    // provider, Texts that omit an explicit `color` parameter fall through to the M3 default of
    // Color.Black, which renders black-on-dark against any of the Scoreboard surfaces. Setting it
    // at the theme root prevents that class of regression by default, so screens are free to
    // background plain Boxes against `colors.floor` without spawning a contrast bug.
    CompositionLocalProvider(
        LocalVestigeColors provides ScoreboardColors,
        LocalVestigeTypography provides ScoreboardTypography,
        LocalVestigeShapes provides ScoreboardShapes,
    ) {
        MaterialTheme(
            colorScheme = VestigeColorScheme,
            typography = M3Typography,
            shapes = M3Shapes,
        ) {
            CompositionLocalProvider(LocalContentColor provides Ink) {
                content()
            }
        }
    }
}
