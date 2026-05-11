package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.PatternState
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import io.objectbox.query.QueryBuilder
import java.time.Clock

/**
 * Persistence + lifecycle owner for `PatternEntity`. Enforces ADR-003 §"Lifecycle & state
 * transitions" — illegal transitions throw, terminal states stick.
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

    fun put(entity: PatternEntity): Long = box.put(entity)

    /**
     * Apply a state transition. Throws [IllegalStateException] when the transition is rejected by
     * ADR-003 — callers handle one-off promotions (e.g. snoozed→active on cooldown expiry) via
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

        // Terminal — dismiss and resolved have no transitions out in v1 per ADR-003.
        PatternState.DISMISSED, PatternState.RESOLVED -> false
    }

    private companion object {
        val ACTIVE_OUT = setOf(
            PatternState.DISMISSED,
            PatternState.SNOOZED,
            PatternState.RESOLVED,
            PatternState.BELOW_THRESHOLD,
        )
        val SNOOZED_OUT = setOf(
            PatternState.ACTIVE,
            PatternState.DISMISSED,
        )
    }
}
