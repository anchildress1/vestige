package dev.anchildress1.vestige.ui.patterns

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anchildress1.vestige.model.PatternState
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.PatternRepo
import dev.anchildress1.vestige.storage.PatternStore
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
import java.time.Clock

/**
 * Drives the Pattern detail screen. Loads the pattern + its supporting entries on construction;
 * action handlers reuse [PatternRepo] so the detail screen and the list share one validator.
 */
class PatternDetailViewModel(
    private val patternId: String,
    private val patternStore: PatternStore,
    private val patternRepo: PatternRepo,
    private val entryStore: EntryStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.systemUTC(),
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

    fun drop() = dispatch(PatternAction.DROP) { patternRepo.drop(patternId) }
    fun skip() = dispatch(PatternAction.SKIP) { patternRepo.skip(patternId) }

    fun restart() {
        viewModelScope.launch {
            val undo = withContext(ioDispatcher) {
                runCatching {
                    val current = patternStore.findByPatternId(patternId)
                        ?: error("PatternDetailViewModel: no pattern row for patternId=$patternId")
                    val priorState = current.state
                    val priorSnoozedUntil = current.snoozedUntil
                    patternRepo.restart(patternId)
                    PatternUndo(
                        patternId = patternId,
                        action = PatternAction.RESTART,
                        previousState = priorState,
                        previousSnoozedUntil = priorSnoozedUntil,
                    )
                }.onFailure { t ->
                    if (t is CancellationException) throw t
                    Log.e(TAG, "restart failed for $patternId", t)
                }
                    .getOrNull()
            }
            _state.value = loadState()
            if (undo != null) _events.emit(PatternActionEvent(patternId, PatternAction.RESTART, undo))
        }
    }

    fun undo(undo: PatternUndo) {
        viewModelScope.launch {
            withContext(ioDispatcher) {
                runCatching {
                    when (undo.action) {
                        PatternAction.DROP -> patternRepo.drop(undo.patternId, undo = true)

                        PatternAction.SKIP -> patternRepo.skip(undo.patternId, undo = true)

                        PatternAction.RESTART -> patternRepo.restart(
                            patternId = undo.patternId,
                            undo = true,
                            previousState = undo.previousState ?: PatternState.ACTIVE,
                            previousSnoozedUntil = undo.previousSnoozedUntil,
                        )
                    }
                }.onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    // Stale undo path (e.g. skip → drop → tap-undo on the older skip snackbar)
                    // sends SNOOZED→ACTIVE through a row already in DROPPED. PatternRepo/
                    // PatternStore throw on illegal transitions per ADR-003; the refresh below
                    // replays the persisted state back onto the detail screen.
                    Log.w(TAG, "Ignoring stale undo for ${undo.patternId}", failure)
                }
            }
            _state.value = loadState()
        }
    }

    @Suppress("kotlin:S6311") // The `mutate` lambdas are non-suspending repo calls; `withContext`
    // is what moves them off the main thread for ObjectBox I/O. Sonar's S6311 sees the suspend
    // signature on the lambda and assumes the dispatcher is redundant; that's a shallow read.
    private fun dispatch(action: PatternAction, mutate: suspend () -> Unit) {
        viewModelScope.launch {
            // A concurrent sweep or double-tap can move the row out of ACTIVE before this lands;
            // PatternStore then throws on the now-illegal transition. Swallow it, replay
            // persisted truth, and emit no undo for an action that never took effect.
            val applied = withContext(ioDispatcher) {
                runCatching { mutate() }
                    .onFailure { t ->
                        if (t is CancellationException) throw t
                        if (t is IllegalStateException) {
                            Log.w(TAG, "Pattern $action skipped for $patternId — concurrent transition", t)
                        } else {
                            Log.e(TAG, "Pattern $action failed unexpectedly for $patternId", t)
                        }
                    }
                    .isSuccess
            }
            _state.value = loadState()
            if (applied) _events.emit(PatternActionEvent(patternId, action, PatternUndo(patternId, action)))
        }
    }

    private suspend fun loadState(): PatternDetailUiState = withContext(ioDispatcher) {
        val pattern = patternStore.findByPatternId(patternId)
            ?: return@withContext PatternDetailUiState.NotFound
        val totalEntries = entryStore.countCompleted()
        val supporting = pattern.supportingEntries.toList()
        val sources = supporting
            .sortedByDescending { it.timestampEpochMs }
            .map { it.toSourceRow() }
        val traceHits = traceBarHitsFromEntries(supporting, clock.millis())
        pattern.toLoaded(totalEntries, sources, traceHits)
    }

    private fun EntryEntity.toSourceRow() = PatternSourceUi(
        entryId = id,
        dateLabel = formatShortDate(timestampEpochMs),
        snippet = snippetOf(entryText),
    )

    private fun PatternEntity.toLoaded(
        totalEntries: Long,
        sources: List<PatternSourceUi>,
        traceHits: Set<Int>,
    ): PatternDetailUiState.Loaded = PatternDetailUiState.Loaded(
        patternId = patternId,
        title = title,
        templateLabel = templateLabel,
        observation = latestCalloutText,
        supportingCount = supportingEntries.size,
        totalEntryCount = totalEntries,
        lastSeenLabel = formatShortDate(lastSeenTimestamp),
        sources = sources,
        traceHits = traceHits,
        state = state,
        isTerminal = isTerminalState(state),
        terminalLabel = terminalLabelFor(state, stateChangedTimestamp, lastSeenTimestamp),
        availableActions = availableActionsFor(state),
    )

    private companion object {
        const val TAG = "PatternDetailVM"
    }
}
