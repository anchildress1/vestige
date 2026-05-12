package dev.anchildress1.vestige.ui.patterns

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.PatternRepo
import dev.anchildress1.vestige.storage.PatternStore
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

/**
 * Drives Story 3.10's Pattern detail. Loads the pattern + its supporting entries on construction;
 * action handlers reuse [PatternRepo] so the detail screen and the list share one validator.
 */
class PatternDetailViewModel(
    private val patternId: String,
    private val patternStore: PatternStore,
    private val patternRepo: PatternRepo,
    private val entryStore: EntryStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow<PatternDetailUiState>(PatternDetailUiState.Loading)
    val state: StateFlow<PatternDetailUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PatternActionEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<PatternActionEvent> = _events.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = loadState()
        }
    }

    fun dismiss() = dispatch(PatternAction.DISMISSED) { patternRepo.dismiss(patternId) }
    fun snooze() = dispatch(PatternAction.SNOOZED) { patternRepo.snooze(patternId) }
    fun markResolved() = dispatch(PatternAction.MARKED_RESOLVED) { patternRepo.markResolved(patternId) }

    fun undo(undo: PatternUndo) {
        viewModelScope.launch {
            withContext(ioDispatcher) {
                when (undo.action) {
                    PatternAction.DISMISSED -> patternRepo.dismiss(undo.patternId, undo = true)
                    PatternAction.SNOOZED -> patternRepo.snooze(undo.patternId, undo = true)
                    PatternAction.MARKED_RESOLVED -> Unit
                }
            }
            _state.value = loadState()
        }
    }

    private fun dispatch(action: PatternAction, mutate: suspend () -> Unit) {
        viewModelScope.launch {
            withContext(ioDispatcher) { mutate() }
            _state.value = loadState()
            val undo = if (action == PatternAction.MARKED_RESOLVED) null else PatternUndo(patternId, action)
            _events.emit(PatternActionEvent(patternId, action, undo))
        }
    }

    private suspend fun loadState(): PatternDetailUiState = withContext(ioDispatcher) {
        val pattern = patternStore.findByPatternId(patternId)
            ?: return@withContext PatternDetailUiState.NotFound
        val totalEntries = entryStore.countCompleted()
        val sources = pattern.supportingEntries
            .sortedByDescending { it.timestampEpochMs }
            .map { it.toSourceRow() }
        pattern.toLoaded(totalEntries, sources)
    }

    private fun EntryEntity.toSourceRow() = PatternSourceUi(
        entryId = id,
        dateLabel = formatShortDate(timestampEpochMs),
        snippet = snippetOf(entryText),
    )

    private fun PatternEntity.toLoaded(
        totalEntries: Long,
        sources: List<PatternSourceUi>,
    ): PatternDetailUiState.Loaded = PatternDetailUiState.Loaded(
        patternId = patternId,
        title = title,
        templateLabel = templateLabel,
        observation = latestCalloutText,
        supportingCount = supportingEntries.size,
        totalEntryCount = totalEntries,
        lastSeenLabel = formatShortDate(lastSeenTimestamp),
        sources = sources,
        isTerminal = isTerminalState(state),
        terminalLabel = terminalLabelFor(state, stateChangedTimestamp),
    )
}
