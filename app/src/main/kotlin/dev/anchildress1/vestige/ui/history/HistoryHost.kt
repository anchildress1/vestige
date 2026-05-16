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
import kotlinx.coroutines.flow.StateFlow
import java.time.ZoneId

@Composable
@Suppress("LongParameterList") // Route-level host; dataRevision + modifier are structural, not business.
fun HistoryHost( // NOSONAR kotlin:S107
    entryStore: EntryStore,
    persona: Persona,
    onExit: () -> Unit,
    zoneId: ZoneId,
    dataRevision: StateFlow<Long>,
    openRequest: EntryDetailOpenRequest? = null,
    onOpenRequestConsumed: () -> Unit = {},
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
                modifier = modifier,
            )
        }

        else -> {
            BackHandler {
                openEntryId = null
                highlightOnOpen = false
            }
            EntryDetailHost(
                entryId = openEntryId!!,
                entryStore = entryStore,
                zoneId = zoneId,
                dataRevision = dataRevision,
                onBack = {
                    openEntryId = null
                    highlightOnOpen = false
                },
                // Clear detail nav before leaving — openEntryId is rememberSaveable, so without
                // this a later return to History would re-open the stale detail instead of the list.
                onNewEntry = {
                    openEntryId = null
                    highlightOnOpen = false
                    onExit()
                },
                highlightOnOpen = highlightOnOpen,
                modifier = modifier,
            )
        }
    }
}
