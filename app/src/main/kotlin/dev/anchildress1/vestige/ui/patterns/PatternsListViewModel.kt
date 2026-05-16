package dev.anchildress1.vestige.ui.patterns

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anchildress1.vestige.model.PatternState
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
 * Drives the Patterns list. Surfaces ACTIVE / SKIPPED / CLOSED / DROPPED patterns grouped by
 * status section per `ux-copy.md` §"Pattern List". Filter chips are P1 — deferred until the
 * P0 action surface is stable (`spec-pattern-action-buttons.md` §P1.1).
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

            // Below the detection threshold the honest copy is "keep recording", not
            // "nothing repeating" — there has not yet been a detection pass to find anything.
            totalEntries < PATTERN_SURFACE_MIN_ENTRIES ->
                PatternsListUiState.Empty(PatternsListUiState.EmptyReason.NO_ENTRIES, totalEntries.toInt())

            else -> PatternsListUiState.Empty(PatternsListUiState.EmptyReason.NO_PATTERNS, totalEntries.toInt())
        }
    }

    private fun List<PatternEntity>.toCards(totalEntries: Long): List<PatternCardUi> {
        val asOfMs = clock.millis()
        return mapNotNull { it.toCardOrNull(totalEntries, asOfMs) }
    }

    fun drop(patternId: String) = dispatch(patternId, PatternAction.DROP) {
        patternRepo.drop(patternId)
    }

    fun skip(patternId: String) = dispatch(patternId, PatternAction.SKIP) {
        patternRepo.skip(patternId)
    }

    fun restart(patternId: String) {
        viewModelScope.launch {
            val undo = withContext(ioDispatcher) {
                runCatching {
                    val current = patternStore.findByPatternId(patternId)
                        ?: error("PatternsListViewModel: no pattern row for patternId=$patternId")
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
                    // A stale undo (e.g. skip→drop→tap-undo on the older skip snackbar) routes
                    // a SNOOZED→ACTIVE transition through a row already in DROPPED. PatternRepo/
                    // PatternStore throw on illegal lifecycle moves per ADR-003; ignore the
                    // throw so the UI doesn't crash, and the refresh below replays the
                    // persisted state back onto the list.
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
            backLabel = snoozedUntil
                ?.takeIf { section == PatternSection.SKIPPED }
                ?.let { formatShortDate(it) },
        )
    }

    private companion object {
        const val TAG = "PatternsListVM"

        // The pattern detector runs once every 10 committed entries; below that there is
        // nothing for it to have surfaced yet.
        const val PATTERN_SURFACE_MIN_ENTRIES = 10L
    }
}
