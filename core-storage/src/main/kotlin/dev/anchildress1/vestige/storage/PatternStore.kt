package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.PatternState
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import io.objectbox.query.QueryBuilder
import java.time.Clock

/**
 * Persistence + lifecycle owner for `PatternEntity`. Enforces ADR-003 §"Lifecycle & state
 * transitions" (as amended 2026-05-13 / 2026-05-13b) — illegal transitions throw, the `DROPPED`
 * and `CLOSED` terminals only leave via the [PatternRepo] undo/restart bypass.
 *
 * Read paths are public; write paths funnel through [transitionState] so detection / repo /
 * re-eval share one validator.
 */
class PatternStore(private val boxStore: BoxStore, private val clock: Clock = Clock.systemUTC()) {

    private val box get() = boxStore.boxFor<PatternEntity>()

    fun findByPatternId(patternId: String): PatternEntity? = box.query()
        .equal(PatternEntity_.patternId, patternId, QueryBuilder.StringOrder.CASE_SENSITIVE)
        .build()
        .use { it.findFirst() }

    fun all(): List<PatternEntity> = box.all

    /**
     * Query only ACTIVE rows — indexed lookup via the stored state serial. Used by the
     * orchestrator's per-entry callout selection, which otherwise paid for a full-table scan
     * on every committed entry.
     */
    fun findActive(): List<PatternEntity> = box.query()
        .equal(PatternEntity_.state, PatternState.ACTIVE.serial, QueryBuilder.StringOrder.CASE_SENSITIVE)
        .build()
        .use { it.find() }

    /** ACTIVE rows ordered most-recently-seen first — drives the Patterns list card order. */
    fun findActiveSortedByLastSeen(): List<PatternEntity> = findActive().sortedByDescending { it.lastSeenTimestamp }

    /** Indexed lookup of `SNOOZED` rows — drives the cold-start skip wake-up sweep. */
    fun findSnoozed(): List<PatternEntity> = box.query()
        .equal(PatternEntity_.state, PatternState.SNOOZED.serial, QueryBuilder.StringOrder.CASE_SENSITIVE)
        .build()
        .use { it.find() }

    /**
     * All rows the Patterns list surfaces — ACTIVE / SNOOZED / CLOSED / DROPPED, ordered
     * most-recently-seen first. BELOW_THRESHOLD is an internal-only state per ADR-003 and stays
     * hidden. Callers slice by [PatternEntity.state] to render the status sections.
     */
    fun findVisibleSortedByLastSeen(): List<PatternEntity> = box.all
        .asSequence()
        .filter { it.state != PatternState.BELOW_THRESHOLD }
        .sortedByDescending { it.lastSeenTimestamp }
        .toList()

    fun put(entity: PatternEntity): Long = box.put(entity)

    /**
     * Cold-start skip wake-up per `spec-pattern-action-buttons.md` §P0.5 / ADR-003 Addendum
     * 2026-05-13. Promotes every `SNOOZED` row whose `snoozedUntil` has elapsed back to `ACTIVE`
     * (clearing `snoozedUntil`). A simple date check on load — not a WorkManager job. Returns the
     * promoted `patternId`s for logging / tests.
     */
    fun promoteExpiredSkips(): List<String> {
        val nowMs = clock.millis()
        return findSnoozed()
            .filter { row -> row.snoozedUntil?.let { it <= nowMs } == true }
            .mapNotNull { row ->
                // One row losing its SNOOZED state to a concurrent writer between the query and
                // here must not strand the rest of the expired cohort — promote per row.
                runCatching { transitionState(row.patternId, PatternState.ACTIVE).patternId }.getOrNull()
            }
    }

    /**
     * Apply a state transition. Throws [IllegalStateException] when the transition is rejected by
     * ADR-003 — callers handle one-off promotions (e.g. snoozed→active on skip-window expiry) via
     * the same hook so the validator stays in one place.
     */
    fun transitionState(patternId: String, target: PatternState, snoozedUntilMs: Long? = null): PatternEntity {
        val entity = findByPatternId(patternId)
            ?: error("PatternStore.transitionState: no pattern row for patternId=$patternId")
        require(target != PatternState.BELOW_THRESHOLD || snoozedUntilMs == null) {
            "BELOW_THRESHOLD never carries a snoozedUntil"
        }
        require(target != PatternState.SNOOZED || (snoozedUntilMs != null && snoozedUntilMs > clock.millis())) {
            "SNOOZED requires snoozedUntilMs > now"
        }
        check(isLegal(entity.state, target)) {
            "Illegal pattern state transition: ${entity.state}->$target (patternId=$patternId)"
        }
        entity.state = target
        entity.snoozedUntil = if (target == PatternState.SNOOZED) snoozedUntilMs else null
        entity.stateChangedTimestamp = clock.millis()
        box.put(entity)
        return entity
    }

    private fun isLegal(from: PatternState, to: PatternState): Boolean = when (from) {
        PatternState.ACTIVE -> to in ACTIVE_OUT

        PatternState.SNOOZED -> to in SNOOZED_OUT

        PatternState.BELOW_THRESHOLD -> to == PatternState.ACTIVE

        // DROPPED / CLOSED only leave via the PatternRepo undo/restart bypass per ADR-003
        // Addendum 2026-05-13b — the strict validator still rejects them so every non-bypass
        // write stays honest.
        PatternState.DROPPED, PatternState.CLOSED -> false
    }

    private companion object {
        // No path into CLOSED in v1 — model-detected close is v1.5 (`pattern-auto-close`).
        val ACTIVE_OUT = setOf(
            PatternState.DROPPED,
            PatternState.SNOOZED,
            PatternState.BELOW_THRESHOLD,
        )
        val SNOOZED_OUT = setOf(
            PatternState.ACTIVE,
            PatternState.DROPPED,
        )
    }
}
