package dev.anchildress1.vestige.storage

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id

/**
 * Singleton row for the global pattern-callout cooldown per ADR-003 §"Cooldown (callout-side
 * only, global)". Exactly one row is maintained; the auto-id is whatever ObjectBox assigns on
 * first insert. Per-pattern cooldown is explicitly rejected by the ADR.
 */
@Entity
class CalloutCooldownEntity(
    @Id var id: Long = 0,
    var lastCalloutEntryId: Long? = null,
    var lastCalloutTimestamp: Long? = null,
    /** Entries to suppress callouts on, counting down toward 0. */
    var remainingSuppression: Int = 0,
)
