package dev.anchildress1.vestige.storage

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

/**
 * Per-pattern cooldown row per ADR-016. One row per `patternId`; the row tracks that pattern's
 * fire history, its in-flight reservation, and its remaining-suppression counter. Replaces the
 * singleton model ADR-003 §"Cooldown (callout-side only, global)" originally specified.
 */
@Entity
class CalloutCooldownEntity(
    @Id var id: Long = 0,
    @Index var patternId: String = "",
    var lastCalloutEntryId: Long? = null,
    var lastCalloutTimestamp: Long? = null,
    /** Entries to suppress this pattern's callout on, counting down toward 0. */
    var remainingSuppression: Int = 0,
    /** Entry currently holding this pattern's callout slot until append either confirms or fails. */
    var pendingCalloutEntryId: Long? = null,
)
