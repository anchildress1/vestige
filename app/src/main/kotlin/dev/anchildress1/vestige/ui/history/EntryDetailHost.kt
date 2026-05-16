package dev.anchildress1.vestige.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.anchildress1.vestige.storage.EntryStore
import java.time.ZoneId

/** Wires [EntryDetailViewModel] to [EntryDetailScreen]. */
@Suppress("LongParameterList")
@Composable
fun EntryDetailHost(
    entryId: Long,
    entryStore: EntryStore,
    zoneId: ZoneId,
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
