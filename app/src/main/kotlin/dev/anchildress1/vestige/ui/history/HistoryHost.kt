package dev.anchildress1.vestige.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
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
fun HistoryHost(
    entryStore: EntryStore,
    persona: Persona,
    onExit: () -> Unit,
    zoneId: ZoneId,
    dataRevision: StateFlow<Long>,
    modifier: Modifier = Modifier,
) {
    var openEntryId by rememberSaveable { mutableStateOf<Long?>(null) }
    val viewModel: HistoryViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HistoryViewModel(entryStore, zoneId, dataRevision = dataRevision) as T
        },
    )

    when (openEntryId) {
        null -> {
            BackHandler(onBack = onExit)
            HistoryScreen(
                viewModel = viewModel,
                persona = persona,
                onEntryClick = { openEntryId = it },
                modifier = modifier,
            )
        }

        else -> {
            BackHandler { openEntryId = null }
            EntryDetailHost(
                entryId = openEntryId!!,
                entryStore = entryStore,
                personaName = persona.name,
                zoneId = zoneId,
                onBack = { openEntryId = null },
                onNewEntry = onExit,
                modifier = modifier,
            )
        }
    }
}
