package dev.anchildress1.vestige.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.anchildress1.vestige.storage.EntryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.ZoneId

/** Wires [EntryDetailViewModel] to [EntryDetailScreen]. */
@Suppress("LongParameterList") // Route-level host; dataRevision + modifier are structural, not business.
@Composable
fun EntryDetailHost( // NOSONAR kotlin:S107
    entryId: Long,
    entryStore: EntryStore,
    zoneId: ZoneId,
    dataRevision: StateFlow<Long> = MutableStateFlow(0L),
    onBack: () -> Unit,
    onNewEntry: () -> Unit,
    highlightOnOpen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val viewModel = remember(entryId, entryStore, zoneId) {
        EntryDetailViewModel(
            entryId = entryId,
            entryStore = entryStore,
            zoneId = zoneId,
            dataRevision = dataRevision,
        )
    }
    EntryDetailScreen(
        viewModel = viewModel,
        onBack = onBack,
        onNewEntry = onNewEntry,
        highlightOnOpen = highlightOnOpen,
        modifier = modifier,
    )
}
