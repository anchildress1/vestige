package dev.anchildress1.vestige.ui.patterns

import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import dev.anchildress1.vestige.ui.theme.Glow
import dev.anchildress1.vestige.ui.theme.Ink
import dev.anchildress1.vestige.ui.theme.S2

/**
 * Snackbar host with explicit accent on the `Undo` action.
 *
 * M3's default snackbar action draws in `inversePrimary` over `inverseSurface`. With our
 * Ink-as-primary palette that combo gives near-zero contrast — the Undo button renders, but
 * users can't see or hit it. This host paints `actionColor = Glow` so the affordance reads.
 */
@Composable
fun PatternSnackbarHost(state: SnackbarHostState) {
    SnackbarHost(hostState = state) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = S2,
            contentColor = Ink,
            actionColor = Glow,
            actionContentColor = Glow,
        )
    }
}
