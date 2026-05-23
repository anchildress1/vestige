package dev.anchildress1.vestige.ui.history

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anchildress1.vestige.storage.EntryStore
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.ZoneOffset

data class HistoryUiState(val entries: ImmutableList<HistorySummary> = persistentListOf(), val loading: Boolean = true)

class HistoryViewModel(
    private val entryStore: EntryStore,
    private val zoneId: ZoneId = ZoneOffset.UTC,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    dataRevision: StateFlow<Long> = MutableStateFlow(0L),
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            dataRevision.collectLatest { load() }
        }
    }

    private suspend fun load() {
        val rows = runCatching {
            withContext(ioDispatcher) { entryStore.listCompleted(limit = LIST_LIMIT) }
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to load history", e)
            emptyList()
        }
        _state.value = HistoryUiState(
            entries = rows.map { entity -> HistorySummary.from(entity, zoneId) }.toImmutableList(),
            loading = false,
        )
    }

    private companion object {
        private const val LIST_LIMIT = 100
        private const val TAG = "HistoryViewModel"
    }
}
