package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.PatternState
import java.time.Clock

/**
 * Action surface for the Patterns view. Each action funnels through
 * [PatternStore.transitionState] so ADR-003's lifecycle invariants are enforced in one place.
 *
 * User actions are `Drop` / `Skip` (on ACTIVE) and `Restart` (on any non-active visible state)
 * per `spec-pattern-action-buttons.md` + ADR-003 Addendum 2026-05-13b. The persisted "skip"
 * state is `SNOOZED` (field `snoozedUntil`) — the user-facing label is "Skip", the state name
 * stays `SNOOZED` per the ADR. `Mark resolved` was retired: closure is model-detected only
 * (`pattern-auto-close`), never user-declared.
 *
 * `undo = true` re-issues the inverse transition for the action just taken — `drop(..., undo
 * = true)` brings a `DROPPED` row back to `ACTIVE`. v1 only exposes undo through the snackbar
 * that follows the action, so callers re-issue while that affordance is alive.
 */
class PatternRepo(private val store: PatternStore, private val clock: Clock = Clock.systemUTC()) {

    fun drop(patternId: String, undo: Boolean = false) {
        if (undo) {
            // Forced un-drop — `DROPPED` only leaves via the snackbar undo / Restart bypass per
            // ADR-003. Refuse to undo a non-dropped row: a stale snackbar callback firing on a
            // CLOSED pattern would otherwise reopen a model-terminal row.
            forceTo(patternId, PatternState.ACTIVE, expectedFrom = PatternState.DROPPED)
        } else {
            store.transitionState(patternId, PatternState.DROPPED)
        }
    }

    fun skip(patternId: String, days: Long = DEFAULT_SKIP_DAYS, undo: Boolean = false) {
        if (undo) {
            // Validator already restricts `SNOOZED → ACTIVE` to legal transitions; add a state
            // precondition so a misrouted snackbar callback can't reopen an ACTIVE-stayed-active
            // row (where `transitionState` would throw on the self-loop anyway, but the message
            // is clearer at the repo boundary).
            requireCurrentState(patternId, PatternState.SNOOZED)
            store.transitionState(patternId, PatternState.ACTIVE)
        } else {
            val until = clock.millis() + days * MILLIS_PER_DAY
            store.transitionState(patternId, PatternState.SNOOZED, snoozedUntilMs = until)
        }
    }

    private fun requireCurrentState(patternId: String, expected: PatternState) {
        val current = store.findByPatternId(patternId)
            ?: error("PatternRepo: no pattern row for patternId=$patternId")
        require(current.state == expected) {
            "PatternRepo undo requires current state=$expected for patternId=$patternId, found ${current.state}"
        }
    }

    /**
     * Restart — moves a non-active pattern (`DROPPED` / `SNOOZED` / `CLOSED`) back to `ACTIVE`.
     * Undo of a Restart restores the exact state snapshot the row had before the restart,
     * including the original `snoozedUntil` when coming back to `SNOOZED`.
     */
    fun restart(
        patternId: String,
        undo: Boolean = false,
        previousState: PatternState = PatternState.ACTIVE,
        previousSnoozedUntil: Long? = null,
    ) {
        if (undo) {
            forceTo(
                patternId = patternId,
                target = previousState,
                expectedFrom = PatternState.ACTIVE,
                snoozedUntilMs = if (previousState == PatternState.SNOOZED) previousSnoozedUntil else null,
            )
        } else {
            val current = store.findByPatternId(patternId)
                ?: error("PatternRepo: no pattern row for patternId=$patternId")
            require(current.state != PatternState.ACTIVE && current.state != PatternState.BELOW_THRESHOLD) {
                "PatternRepo.restart requires a non-active visible state, found ${current.state}"
            }
            forceTo(patternId, PatternState.ACTIVE, expectedFrom = current.state)
        }
    }

    /**
     * Undo / restart bypass — `DROPPED` and `CLOSED` are terminal to the strict validator, so
     * the only legal exits are the snackbar undo and the Restart action. Writes the row directly
     * rather than routing through [PatternStore.transitionState] so the lifecycle validator
     * stays strict for every non-bypass write. [expectedFrom] is the load-bearing precondition:
     * the row must currently be in that state, otherwise the bypass would let a misrouted
     * snackbar callback rewrite a `CLOSED` (model-terminal) row to `ACTIVE`.
     */
    private fun forceTo(
        patternId: String,
        target: PatternState,
        expectedFrom: PatternState,
        snoozedUntilMs: Long? = null,
    ) {
        val entity = store.findByPatternId(patternId)
            ?: error("PatternRepo undo: no pattern row for patternId=$patternId")
        require(entity.state == expectedFrom) {
            "PatternRepo undo requires current state=$expectedFrom for patternId=$patternId, found ${entity.state}"
        }
        require(target != PatternState.SNOOZED || snoozedUntilMs != null) {
            "PatternRepo forceTo requires snoozedUntilMs when restoring SNOOZED for patternId=$patternId"
        }
        entity.state = target
        entity.snoozedUntil = if (target == PatternState.SNOOZED) snoozedUntilMs else null
        entity.stateChangedTimestamp = clock.millis()
        store.put(entity)
    }

    companion object {
        const val DEFAULT_SKIP_DAYS: Long = 7
        private const val MILLIS_PER_DAY: Long = 24L * 60 * 60 * 1000
    }
}
