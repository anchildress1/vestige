package dev.anchildress1.vestige.ui.patterns

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anchildress1.vestige.model.PatternState
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
import java.time.Clock

/**
 * Drives Story 3.9's Patterns list. Surfaces ACTIVE / SNOOZED / RESOLVED / DISMISSED patterns
 * grouped by status section per `poc/screens-patterns.jsx`. Filter chips that scope the visible
 * sections still ship in Phase 4.
 *
 * Actions delegate to [PatternRepo] so ADR-003 lifecycle invariants stay on one validator.
 * Snackbar undo affordances surface via [events]; the View owns the timeout window.
 */
class PatternsListViewModel(
    private val patternStore: PatternStore,
    private val patternRepo: PatternRepo,
    private val entryStore: EntryStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {

    private val _state = MutableStateFlow<PatternsListUiState>(PatternsListUiState.Loading)
    val state: StateFlow<PatternsListUiState> = _state.asStateFlow()

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

    private suspend fun loadState(): PatternsListUiState = withContext(ioDispatcher) {
        val totalEntries = entryStore.countCompleted()
        val visible = patternStore.findVisibleSortedByLastSeen()
        when {
            visible.isNotEmpty() -> PatternsListUiState.Loaded(visible.toCards(totalEntries))
            totalEntries == 0L -> PatternsListUiState.Empty(PatternsListUiState.EmptyReason.NO_ENTRIES)
            else -> PatternsListUiState.Empty(PatternsListUiState.EmptyReason.NO_PATTERNS)
        }
    }

    private fun List<PatternEntity>.toCards(totalEntries: Long): List<PatternCardUi> {
        val asOfMs = clock.millis()
        return mapNotNull { it.toCardOrNull(totalEntries, asOfMs) }
    }

    fun dismiss(patternId: String) = dispatch(patternId, PatternAction.DISMISSED) {
        patternRepo.dismiss(patternId)
    }

    fun snooze(patternId: String) = dispatch(patternId, PatternAction.SNOOZED) {
        patternRepo.snooze(patternId)
    }

    fun markResolved(patternId: String) = dispatch(patternId, PatternAction.MARKED_RESOLVED) {
        patternRepo.markResolved(patternId)
    }

    fun restart(patternId: String) {
        viewModelScope.launch {
            val previousState = withContext(ioDispatcher) {
                val current = patternStore.findByPatternId(patternId)
                    ?: error("PatternsListViewModel: no pattern row for patternId=$patternId")
                val prior = current.state
                patternRepo.restart(patternId)
                prior
            }
            _state.value = loadState()
            _events.emit(
                PatternActionEvent(
                    patternId,
                    PatternAction.RESTART,
                    PatternUndo(patternId, PatternAction.RESTART, previousState),
                ),
            )
        }
    }

    fun undo(undo: PatternUndo) {
        viewModelScope.launch {
            withContext(ioDispatcher) {
                runCatching {
                    when (undo.action) {
                        PatternAction.DISMISSED -> patternRepo.dismiss(undo.patternId, undo = true)

                        PatternAction.SNOOZED -> patternRepo.snooze(undo.patternId, undo = true)

                        PatternAction.MARKED_RESOLVED -> Unit

                        PatternAction.RESTART -> patternRepo.restart(
                            patternId = undo.patternId,
                            undo = true,
                            previousState = undo.previousState ?: PatternState.ACTIVE,
                        )
                    }
                }.onFailure { failure ->
                    // A stale undo (e.g. snooze→dismiss→tap-undo on the older snooze snackbar)
                    // routes a SNOOZED→ACTIVE transition through a row already in DISMISSED.
                    // PatternRepo/PatternStore throw on illegal lifecycle moves per ADR-003;
                    // ignore the throw so the UI doesn't crash, and the refresh below replays
                    // the persisted state back onto the list.
                    Log.w(TAG, "Ignoring stale undo for ${undo.patternId}", failure)
                }
            }
            _state.value = loadState()
        }
    }

    @Suppress("kotlin:S6311") // The `mutate` lambdas are non-suspending repo calls; `withContext`
    // is what moves them off the main thread for ObjectBox I/O. Sonar's S6311 sees the suspend
    // signature on the lambda and assumes the dispatcher is redundant; that's a shallow read.
    private fun dispatch(patternId: String, action: PatternAction, mutate: suspend () -> Unit) {
        viewModelScope.launch {
            withContext(ioDispatcher) { mutate() }
            _state.value = loadState()
            // markResolved is sticky-terminal per ADR-003; the snackbar surfaces visibility only.
            val undo = if (action == PatternAction.MARKED_RESOLVED) null else PatternUndo(patternId, action)
            _events.emit(PatternActionEvent(patternId, action, undo))
        }
    }

    private fun PatternEntity.toCardOrNull(totalEntries: Long, asOfMs: Long): PatternCardUi? {
        val section = sectionFor(state) ?: return null
        return PatternCardUi(
            patternId = patternId,
            title = title,
            templateLabel = templateLabel,
            observation = latestCalloutText,
            supportingCount = supportingEntries.size,
            totalEntryCount = totalEntries,
            lastSeenLabel = formatShortDate(lastSeenTimestamp),
            section = section,
            traceHits = traceBarHitsFromEntries(supportingEntries.toList(), asOfMs),
            availableActions = availableActionsFor(state),
        )
    }

    private companion object {
        const val TAG = "PatternsListVM"
    }
}
