package dev.anchildress1.vestige.ui.patterns

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable

/**
 * Snackbar host for the pattern surfaces. All colors flow from `VestigeColorScheme`'s
 * inverseSurface / inverseOnSurface / inversePrimary slots — `M3` Snackbar reads them via
 * `SnackbarDefaults.color` without per-call-site overrides. See `Theme.kt` for the slot fills.
 */
@Composable
fun PatternSnackbarHost(state: SnackbarHostState) {
    SnackbarHost(hostState = state)
}
