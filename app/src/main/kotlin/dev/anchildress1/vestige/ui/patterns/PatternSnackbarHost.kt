package dev.anchildress1.vestige.ui.patterns

import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import dev.anchildress1.vestige.ui.theme.Ink
import dev.anchildress1.vestige.ui.theme.S2

// Lime fails AA on S2 contrast; Ink keeps the action accessible. M3's intrinsic action-slot
// styling supplies the affordance differentiation that matching the surface color sacrifices.
internal val PatternSnackbarActionColor = Ink

/**
 * Snackbar host with explicit accent on the `Undo` action.
 *
 * M3's default snackbar action draws in `inversePrimary` over `inverseSurface`. With our
 * Ink-as-primary palette that combo gives near-zero contrast — the action renders but is
 * invisible. Painting [containerColor] / [contentColor] / [actionColor] explicitly keeps the
 * affordance readable.
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
