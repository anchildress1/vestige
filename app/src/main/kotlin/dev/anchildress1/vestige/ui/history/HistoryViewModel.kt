package dev.anchildress1.vestige.ui.history

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anchildress1.vestige.storage.EntryStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HistoryUiState(val entries: List<HistorySummary> = emptyList(), val loading: Boolean = true)

class HistoryViewModel(
    private val entryStore: EntryStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val rows = runCatching {
                withContext(ioDispatcher) { entryStore.listCompleted(limit = LIST_LIMIT) }
            }.onFailure { Log.e(TAG, "Failed to load history", it) }
                .getOrDefault(emptyList())
            _state.value = HistoryUiState(
                entries = rows.map { entity ->
                    HistorySummary.from(
                        id = entity.id,
                        timestampEpochMs = entity.timestampEpochMs,
                        templateLabelSerial = entity.templateLabel?.serial,
                        entryText = entity.entryText,
                        durationMs = entity.durationMs,
                    )
                },
                loading = false,
            )
        }
    }

    private companion object {
        private const val LIST_LIMIT = 100
        private const val TAG = "HistoryViewModel"
    }
}
