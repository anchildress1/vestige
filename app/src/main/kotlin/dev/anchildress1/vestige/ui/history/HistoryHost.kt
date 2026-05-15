package dev.anchildress1.vestige.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
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
    BackHandler(onBack = onExit)
    val viewModel: HistoryViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HistoryViewModel(entryStore, zoneId, dataRevision = dataRevision) as T
        },
    )
    HistoryScreen(
        viewModel = viewModel,
        persona = persona,
        modifier = modifier,
    )
}
