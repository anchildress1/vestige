package dev.anchildress1.vestige.storage

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

/**
 * A surfaced pattern per `concept-locked.md` §"Pattern persistence" and ADR-003. Pattern
 * detection (Phase 3) populates the supporting evidence; the lifecycle state machine
 * (active / dismissed / snoozed / resolved) lives in `PatternStore`.
 */
@Entity
class PatternEntity(
    @Id var id: Long = 0,

    /** Stable hash so the resolver can match an entry's `recurrence_link` to a pattern row. */
    @Index var stableId: String = "",

    /** Pattern primitive type per ADR-003 (e.g. `aftermath-post-meeting`). */
    var type: String = "",

    /** Number of supporting entries currently in the 90-day window. */
    var entryCount: Int = 0,

    /** Lifecycle state: ACTIVE / DISMISSED / SNOOZED / RESOLVED. Stored as a name string. */
    var lifecycleState: String = "ACTIVE",

    /** Last time pattern detection updated this row, epoch millis UTC. */
    var lastObservedEpochMs: Long = 0,
)
