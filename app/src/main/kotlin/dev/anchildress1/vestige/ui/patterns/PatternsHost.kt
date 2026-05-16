package dev.anchildress1.vestige.ui.patterns

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.storage.PatternRepo
import dev.anchildress1.vestige.storage.PatternStore
import dev.anchildress1.vestige.ui.history.EntryDetailHost
import java.time.ZoneId

@Suppress("LongParameterList")
@Composable
fun PatternsHost(
    patternStore: PatternStore,
    patternRepo: PatternRepo,
    entryStore: EntryStore,
    zoneId: ZoneId,
    onExit: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var openPatternId by rememberSaveable { mutableStateOf<String?>(null) }
    var openEntryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var highlightEntryOnOpen by rememberSaveable { mutableStateOf(false) }
    val listViewModel = remember(patternStore, patternRepo, entryStore) {
        PatternsListViewModel(patternStore, patternRepo, entryStore)
    }
    val detailViewModel = remember(openPatternId, patternStore, patternRepo, entryStore) {
        openPatternId?.let { PatternDetailViewModel(it, patternStore, patternRepo, entryStore) }
    }

    when {
        openEntryId != null -> PatternEntryDetailRoute(
            entryId = openEntryId!!,
            entryStore = entryStore,
            zoneId = zoneId,
            highlightOnOpen = highlightEntryOnOpen,
            onClose = {
                openEntryId = null
                highlightEntryOnOpen = false
            },
            // Clear detail nav before leaving — openEntryId is rememberSaveable, so without
            // this a later return to Patterns would re-open the stale detail.
            onNewEntry = {
                openEntryId = null
                highlightEntryOnOpen = false
                onExit()
            },
            modifier = modifier,
        )

        detailViewModel == null -> {
            BackHandler(onBack = onExit)
            PatternsListScreen(
                viewModel = listViewModel,
                onOpenPattern = { openPatternId = it },
                modifier = modifier,
            )
        }

        else -> {
            BackHandler {
                openPatternId = null
                listViewModel.refresh()
            }
            PatternDetailScreen(
                viewModel = detailViewModel,
                onBack = {
                    openPatternId = null
                    listViewModel.refresh()
                },
                onOpenEntry = {
                    openEntryId = it
                    highlightEntryOnOpen = true
                },
                modifier = modifier,
            )
        }
    }
}

@Suppress("LongParameterList") // Route seam: ids + store + zone + nav callbacks + modifier.
@Composable
private fun PatternEntryDetailRoute(
    entryId: Long,
    entryStore: EntryStore,
    zoneId: ZoneId,
    highlightOnOpen: Boolean,
    onClose: () -> Unit,
    onNewEntry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    EntryDetailHost(
        entryId = entryId,
        entryStore = entryStore,
        zoneId = zoneId,
        onBack = onClose,
        onNewEntry = onNewEntry,
        highlightOnOpen = highlightOnOpen,
        modifier = modifier,
    )
}
