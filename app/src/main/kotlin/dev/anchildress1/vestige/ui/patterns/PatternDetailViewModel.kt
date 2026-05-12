package dev.anchildress1.vestige.ui.patterns

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anchildress1.vestige.model.PatternState
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.PatternRepo
import dev.anchildress1.vestige.storage.PatternStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = loadState()
        }
    }

    fun dismiss() = mutate { patternRepo.dismiss(patternId) }
    fun snooze() = mutate { patternRepo.snooze(patternId) }
    fun markResolved() = mutate { patternRepo.markResolved(patternId) }

    private fun mutate(action: suspend () -> Unit) {
        viewModelScope.launch {
            withContext(ioDispatcher) { action() }
            _state.value = loadState()
        }
    }

    private suspend fun loadState(): PatternDetailUiState = withContext(ioDispatcher) {
        val pattern = patternStore.findByPatternId(patternId)
            ?: return@withContext PatternDetailUiState.NotFound
        val totalEntries = entryStore.count()
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
    ): PatternDetailUiState.Loaded {
        val terminalLabel = when (state) {
            PatternState.RESOLVED -> "Marked resolved ${formatShortDate(stateChangedTimestamp)}."
            PatternState.DISMISSED -> "Dismissed ${formatShortDate(stateChangedTimestamp)}."
            else -> null
        }
        val isTerminal = state == PatternState.RESOLVED || state == PatternState.DISMISSED
        return PatternDetailUiState.Loaded(
            patternId = patternId,
            title = title,
            templateLabel = templateLabel,
            observation = latestCalloutText,
            supportingCount = supportingEntries.size,
            totalEntryCount = totalEntries,
            lastSeenLabel = formatShortDate(lastSeenTimestamp),
            sources = sources,
            isTerminal = isTerminal,
            terminalLabel = terminalLabel,
        )
    }
}
