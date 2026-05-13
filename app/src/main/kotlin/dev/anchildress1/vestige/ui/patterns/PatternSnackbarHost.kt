package dev.anchildress1.vestige.ui.patterns

import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import dev.anchildress1.vestige.ui.theme.Ink
import dev.anchildress1.vestige.ui.theme.S2

internal val PatternSnackbarActionColor = Ink

/**
 * Snackbar host with explicit accent on the `Undo` action.
 *
 * M3's default snackbar action draws in `inversePrimary` over `inverseSurface`. With our
 * Ink-as-primary palette that combo gives near-zero contrast. Keep the action on Ink as well so
 * the affordance clears the story's 4.5:1 floor on S2.
 */
@Composable
fun PatternSnackbarHost(state: SnackbarHostState) {
    SnackbarHost(hostState = state) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = S2,
            contentColor = Ink,
            actionColor = PatternSnackbarActionColor,
            actionContentColor = PatternSnackbarActionColor,
        )
    }
}
