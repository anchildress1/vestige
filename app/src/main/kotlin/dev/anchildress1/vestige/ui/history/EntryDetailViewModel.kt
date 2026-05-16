package dev.anchildress1.vestige.ui.history

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anchildress1.vestige.storage.EntryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    // One-shot effect: emitted after a successful delete so the host can pop back.
    private val _deleteComplete = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val deleteComplete: SharedFlow<Unit> = _deleteComplete.asSharedFlow()

    init {
        load()
    }

    fun delete() {
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) { entryStore.deleteEntry(entryId) }
            }.onSuccess {
                _deleteComplete.tryEmit(Unit)
            }.onFailure { e ->
                if (e is CancellationException) throw e
                Log.e(TAG, "deleteEntry failed for id=$entryId", e)
            }
        }
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
