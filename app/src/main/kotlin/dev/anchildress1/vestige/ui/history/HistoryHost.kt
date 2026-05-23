package dev.anchildress1.vestige.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.storage.PatternStore
import dev.anchildress1.vestige.ui.components.BottomTab
import kotlinx.coroutines.flow.StateFlow
import java.time.ZoneId

@Composable
@Suppress("LongParameterList") // Route-level host; dataRevision + modifier are structural, not business.
fun HistoryHost( // NOSONAR kotlin:S107
    entryStore: EntryStore,
    patternStore: PatternStore,
    persona: Persona,
    onExit: () -> Unit,
    zoneId: ZoneId,
    dataRevision: StateFlow<Long>,
    openRequest: EntryDetailOpenRequest? = null,
    onOpenRequestConsumed: () -> Unit = {},
    onNavigateTab: (BottomTab) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var openEntryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var highlightOnOpen by rememberSaveable { mutableStateOf(false) }
    val viewModel: HistoryViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HistoryViewModel(entryStore, zoneId, dataRevision = dataRevision) as T
        },
    )
    LaunchedEffect(openRequest?.token) {
        val request = openRequest ?: return@LaunchedEffect
        openEntryId = request.entryId
        highlightOnOpen = request.highlightOnOpen
        onOpenRequestConsumed()
    }

    when (openEntryId) {
        null -> {
            BackHandler(onBack = onExit)
            HistoryScreen(
                viewModel = viewModel,
                persona = persona,
                onEntryClick = {
                    openEntryId = it
                    highlightOnOpen = false
                },
                onNavSelect = { tab ->
                    // HISTORY tab is the current screen — only CAPTURE / PATTERNS route away.
                    if (tab != BottomTab.HISTORY) onNavigateTab(tab)
                },
                onMenuTap = onOpenSettings,
                modifier = modifier,
            )
        }

        else -> HistoryDetailRoute(
            entryId = openEntryId!!,
            entryStore = entryStore,
            patternStore = patternStore,
            zoneId = zoneId,
            dataRevision = dataRevision,
            highlightOnOpen = highlightOnOpen,
            // Clear detail nav before leaving — openEntryId is rememberSaveable, so without
            // this a later return to History would re-open the stale detail, not the list.
            onClearDetail = {
                openEntryId = null
                highlightOnOpen = false
            },
            onExit = onExit,
            onNavigateTab = onNavigateTab,
            onOpenSettings = onOpenSettings,
            modifier = modifier,
        )
    }
}

@Suppress("LongParameterList") // Route seam: ids + store + zone + nav callbacks + modifier.
@Composable
private fun HistoryDetailRoute( // NOSONAR kotlin:S107
    entryId: Long,
    entryStore: EntryStore,
    patternStore: PatternStore,
    zoneId: ZoneId,
    dataRevision: StateFlow<Long>,
    highlightOnOpen: Boolean,
    onClearDetail: () -> Unit,
    onExit: () -> Unit,
    onNavigateTab: (BottomTab) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClearDetail)
    EntryDetailHost(
        entryId = entryId,
        entryStore = entryStore,
        patternStore = patternStore,
        zoneId = zoneId,
        dataRevision = dataRevision,
        onBack = onClearDetail,
        onNewEntry = {
            onClearDetail()
            onExit()
        },
        onNavSelect = { tab ->
            // HISTORY from a detail page = pop back to the list, not a re-entry.
            onClearDetail()
            if (tab != BottomTab.HISTORY) onNavigateTab(tab)
        },
        onMenuTap = onOpenSettings,
        highlightOnOpen = highlightOnOpen,
        modifier = modifier,
    )
}
