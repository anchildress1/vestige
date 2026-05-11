package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.PatternState
import java.time.Clock

/**
 * Action surface for the Patterns view. Each action funnels through
 * [PatternStore.transitionState] so ADR-003's lifecycle invariants are enforced in one place.
 *
 * `undo = true` re-issues the inverse transition for the action just taken — `dismiss(..., undo
 * = true)` brings a `DISMISSED` row back to `ACTIVE`. v1 only exposes undo through the
 * snackbar that follows the action, so callers re-issue while that affordance is alive; there
 * is no other path out of the terminal states.
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

    /**
     * Sticky per ADR-003 §"Mark-resolved is sticky for the demo." No undo path in v1 — the
     * ADR is explicit that "reopening is a backlog candidate" and that mark-resolved
     * "respects user agency … if they kill it, they killed it."
     */
    fun markResolved(patternId: String) {
        store.transitionState(patternId, PatternState.RESOLVED)
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
