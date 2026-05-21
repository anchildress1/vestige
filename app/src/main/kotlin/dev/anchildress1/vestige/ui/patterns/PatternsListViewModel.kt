package dev.anchildress1.vestige.ui.patterns

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anchildress1.vestige.patterns.PatternDetectionOrchestrator
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
            visible.isNotEmpty() -> PatternsListUiState.Loaded(
                cards = visible.toCards(totalEntries),
                entryCount = totalEntries.toInt(),
                daysSinceFirstCapped = daysSinceFirstEntry(),
            )

            // Below the detection threshold the honest copy is "keep recording", not
            // "nothing repeating" — there has not yet been a detection pass to find anything.
            totalEntries < PatternDetectionOrchestrator.PATTERN_DETECTION_CADENCE ->
                PatternsListUiState.Empty(PatternsListUiState.EmptyReason.NO_ENTRIES, totalEntries.toInt())

            else -> PatternsListUiState.Empty(PatternsListUiState.EmptyReason.NO_PATTERNS, totalEntries.toInt())
        }
    }

    private fun daysSinceFirstEntry(): Int {
        val first = entryStore.firstCompleted() ?: return 1
        val elapsedMs = (clock.millis() - first.timestampEpochMs).coerceAtLeast(0L)
        return ((elapsedMs / MS_PER_DAY).toInt() + 1).coerceIn(1, PatternsListUiState.MAX_STAT_DAYS)
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
                    PatternUndo.Restart(
                        patternId = patternId,
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
                    when (undo) {
                        is PatternUndo.Drop -> patternRepo.drop(undo.patternId, undo = true)

                        is PatternUndo.Skip -> patternRepo.skip(undo.patternId, undo = true)

                        is PatternUndo.Restart -> patternRepo.restart(
                            patternId = undo.patternId,
                            undo = true,
                            previousState = undo.previousState,
                            previousSnoozedUntil = undo.previousSnoozedUntil,
                        )
                    }
                }.onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    // A stale undo (e.g. skip→drop→tap-undo on the older skip snackbar) routes
                    // a SNOOZED→ACTIVE transition through a row already in DROPPED. PatternRepo/
                    // PatternStore throw on illegal lifecycle moves; ignore the throw so the UI
                    // doesn't crash, and the refresh below replays the persisted state onto the list.
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
            if (applied) {
                val undo: PatternUndo = when (action) {
                    PatternAction.DROP -> PatternUndo.Drop(patternId)
                    PatternAction.SKIP -> PatternUndo.Skip(patternId)
                    PatternAction.RESTART -> error("restart dispatched through wrong path")
                }
                _events.emit(PatternActionEvent(patternId, action, undo))
            }
        }
    }

    private fun PatternEntity.toCardOrNull(totalEntries: Long, asOfMs: Long): PatternCardUi? {
        val section = sectionFor(state) ?: return null
        return PatternCardUi(
            patternId = patternId,
            kindLabel = patternKindLabel(kind),
            title = title,
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
        const val MS_PER_DAY: Long = 24L * 60 * 60 * 1000
    }
}
