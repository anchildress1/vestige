package dev.anchildress1.vestige.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.storage.EntryStore
import java.time.ZoneId

@Composable
fun HistoryHost(
    entryStore: EntryStore,
    persona: Persona,
    onExit: () -> Unit,
    zoneId: ZoneId,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onExit)
    val viewModel = remember(entryStore, zoneId) { HistoryViewModel(entryStore, zoneId) }
    HistoryScreen(
        viewModel = viewModel,
        persona = persona,
        modifier = modifier,
    )
}
