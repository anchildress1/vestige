package dev.anchildress1.vestige.ui.patterns

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import dev.anchildress1.vestige.R
import kotlinx.coroutines.flow.Flow

/**
 * Snackbar host for the pattern surfaces. All colors flow from `VestigeColorScheme`'s
 * inverseSurface / inverseOnSurface / inversePrimary slots — `M3` Snackbar reads them via
 * `SnackbarDefaults.color` without per-call-site overrides. See `Theme.kt` for the slot fills.
 */
@Composable
fun PatternSnackbarHost(state: SnackbarHostState) {
    SnackbarHost(hostState = state)
}

@Composable
internal fun rememberPatternSnackbarHostState(
    events: Flow<PatternActionEvent>,
    onUndo: (PatternUndo) -> Unit,
): SnackbarHostState {
    val state = remember { SnackbarHostState() }
    val currentOnUndo by rememberUpdatedState(onUndo)

    val droppedMessage = stringResource(R.string.snackbar_dismissed)
    val skippedMessage = stringResource(R.string.snackbar_snoozed_7_days)
    val restartMessage = stringResource(R.string.snackbar_pattern_back)
    val undoLabel = stringResource(R.string.pattern_undo)

    LaunchedEffect(events, state, droppedMessage, skippedMessage, restartMessage, undoLabel) {
        events.collect { event ->
            val result = state.showSnackbar(
                message = when (event.action) {
                    PatternAction.DROP -> droppedMessage
                    PatternAction.SKIP -> skippedMessage
                    PatternAction.RESTART -> restartMessage
                },
                actionLabel = if (event.undo != null) undoLabel else null,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed && event.undo != null) {
                currentOnUndo(event.undo)
            }
        }
    }

    return state
}
