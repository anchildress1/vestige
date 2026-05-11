package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.PatternState
import java.time.Clock

/**
 * Action surface for the Patterns view per Story 3.8. Each action funnels through
 * [PatternStore.transitionState] so ADR-003's lifecycle invariants are enforced in one place.
 *
 * `undo = true` re-issues the inverse transition for the action just taken — `dismiss(..., undo
 * = true)` brings a `DISMISSED` row back to `ACTIVE`. The UI affordance is a 5-second snackbar
 * (Story 3.9), so callers re-issue while the snackbar is alive; v1 does not surface an undo
 * path past that window.
 */
class PatternRepo(private val store: PatternStore, private val clock: Clock = Clock.systemUTC()) {

    fun dismiss(patternId: String, undo: Boolean = false) {
        if (undo) {
            // Forced un-dismiss — `DISMISSED` is normally terminal per ADR-003. The undo path
            // is the only legal way out and is bounded by the snackbar window the UI exposes.
            forceTo(patternId, PatternState.ACTIVE)
        } else {
            store.transitionState(patternId, PatternState.DISMISSED)
        }
    }

    fun snooze(patternId: String, days: Long = DEFAULT_SNOOZE_DAYS, undo: Boolean = false) {
        if (undo) {
            store.transitionState(patternId, PatternState.ACTIVE)
        } else {
            val until = clock.millis() + days * MILLIS_PER_DAY
            store.transitionState(patternId, PatternState.SNOOZED, snoozedUntilMs = until)
        }
    }

    fun markResolved(patternId: String, undo: Boolean = false) {
        if (undo) {
            forceTo(patternId, PatternState.ACTIVE)
        } else {
            store.transitionState(patternId, PatternState.RESOLVED)
        }
    }

    /**
     * Undo bypass — the only legal exit from `DISMISSED` / `RESOLVED` is the snackbar undo.
     * Writes the row directly rather than routing through [PatternStore.transitionState] so the
     * lifecycle validator stays strict for every non-undo write.
     */
    private fun forceTo(patternId: String, target: PatternState) {
        val entity = store.findByPatternId(patternId)
            ?: error("PatternRepo undo: no pattern row for patternId=$patternId")
        entity.state = target
        entity.snoozedUntil = null
        entity.stateChangedTimestamp = clock.millis()
        store.put(entity)
    }

    companion object {
        const val DEFAULT_SNOOZE_DAYS: Long = 7
        private const val MILLIS_PER_DAY: Long = 24L * 60 * 60 * 1000
    }
}
