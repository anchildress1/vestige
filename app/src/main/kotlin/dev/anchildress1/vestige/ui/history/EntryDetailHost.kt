package dev.anchildress1.vestige.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.ui.components.BottomTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.ZoneId

/**
 * Wires [EntryDetailViewModel] to [EntryDetailScreen].
 *
 * `onNewEntry` / `highlightOnOpen` are retained on the host signature so the nav graph
 * (PatternsHost / HistoryHost / EntryDetailOpenRequest) is untouched, but the redesigned
 * detail screen (`poc/entry-full-final.png`) dropped the +NEW-ENTRY action and the
 * source-highlight, so they are no longer forwarded.
 */
@Suppress("LongParameterList", "UNUSED_PARAMETER") // Route host; signature kept to avoid a nav-graph ripple.
@Composable
fun EntryDetailHost( // NOSONAR kotlin:S107
    entryId: Long,
    entryStore: EntryStore,
    zoneId: ZoneId,
    dataRevision: StateFlow<Long> = MutableStateFlow(0L),
    onBack: () -> Unit,
    onNewEntry: () -> Unit,
    onNavSelect: (BottomTab) -> Unit = {},
    onMenuTap: (() -> Unit)? = null,
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
        onNavSelect = onNavSelect,
        onMenuTap = onMenuTap,
        modifier = modifier,
    )
}
