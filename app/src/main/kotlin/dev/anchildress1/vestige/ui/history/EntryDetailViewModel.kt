package dev.anchildress1.vestige.ui.history

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anchildress1.vestige.storage.EntryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

class EntryDetailViewModel(
    private val entryId: Long,
    private val entryStore: EntryStore,
    private val personaName: String,
    private val zoneId: ZoneId,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow<EntryDetailUiState>(EntryDetailUiState.Loading)
    val state: StateFlow<EntryDetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val entity = runCatching {
                withContext(ioDispatcher) { entryStore.readEntry(entryId) }
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                Log.e(TAG, "readEntry failed for id=$entryId", e)
                null
            }
            _state.value = when (entity) {
                null -> EntryDetailUiState.NotFound
                else -> EntryDetailUiState.Loaded(EntryDetailUiModel.from(entity, personaName, zoneId))
            }
        }
    }

    private companion object {
        private const val TAG = "EntryDetailViewModel"
    }
}
