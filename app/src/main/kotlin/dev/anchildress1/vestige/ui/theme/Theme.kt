package dev.anchildress1.vestige.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val VestigeColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    surfaceContainer = SurfaceContainerDark,
    onSurface = OnSurfaceDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
)

@Composable
fun VestigeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VestigeColorScheme,
        typography = VestigeTypography,
        content = content,
    )
}
