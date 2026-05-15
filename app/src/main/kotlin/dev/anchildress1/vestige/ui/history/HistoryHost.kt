package dev.anchildress1.vestige.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.storage.EntryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.ZoneId

@Composable
@Suppress("LongParameterList") // Route-level host; dataRevision + modifier are structural, not business.
fun HistoryHost(
    entryStore: EntryStore,
    persona: Persona,
    onExit: () -> Unit,
    zoneId: ZoneId,
    dataRevision: StateFlow<Long> = MutableStateFlow(0L),
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onExit)
    val viewModel = remember(entryStore, zoneId) { HistoryViewModel(entryStore, zoneId, dataRevision = dataRevision) }
    HistoryScreen(
        viewModel = viewModel,
        persona = persona,
        modifier = modifier,
    )
}
