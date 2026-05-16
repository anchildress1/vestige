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
    personaName: String,
    zoneId: ZoneId,
    onBack: () -> Unit,
    onNewEntry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = remember(entryId, entryStore, personaName, zoneId) {
        EntryDetailViewModel(
            entryId = entryId,
            entryStore = entryStore,
            personaName = personaName,
            zoneId = zoneId,
        )
    }
    EntryDetailScreen(
        viewModel = viewModel,
        onBack = onBack,
        onNewEntry = onNewEntry,
        modifier = modifier,
    )
}
