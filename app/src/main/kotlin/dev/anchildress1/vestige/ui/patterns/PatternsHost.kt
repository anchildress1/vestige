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

/**
 * Lightweight in-process navigation between list and detail. Story 3.9 / 3.10 both call out
 * "rough navigation" — polish is Phase 4, which adds androidx.navigation. [onExit] unwinds to
 * whatever surface hosts the patterns experience (Phase-1 shell today).
 */
@Composable
fun PatternsHost(
    patternStore: PatternStore,
    patternRepo: PatternRepo,
    entryStore: EntryStore,
    onExit: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var openPatternId by rememberSaveable { mutableStateOf<String?>(null) }
    val listViewModel = remember(patternStore, patternRepo, entryStore) {
        PatternsListViewModel(patternStore, patternRepo, entryStore)
    }
    val detailViewModel = remember(openPatternId, patternStore, patternRepo, entryStore) {
        openPatternId?.let { PatternDetailViewModel(it, patternStore, patternRepo, entryStore) }
    }

    if (detailViewModel == null) {
        BackHandler(onBack = onExit)
        PatternsListScreen(
            viewModel = listViewModel,
            onOpenPattern = { openPatternId = it },
            modifier = modifier,
        )
    } else {
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
            modifier = modifier,
        )
    }
}
