package dev.anchildress1.vestige.storage

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

/**
 * Per-pattern callout-cooldown row. One row per `patternId`; tracks fire history, in-flight
 * reservation, and remaining-suppression counter. `remainingSuppression > 0` and
 * `pendingCalloutEntryId != null` are mutually exclusive in valid states (suppression starts
 * only after a reservation confirms; reserving requires a clear window).
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
) {
    init {
        // ObjectBox-hydrated rows always carry the real patternId; this fence catches a
        // default-constructed row that bypassed snapshotFor's create path.
        require(patternId.isNotBlank()) { "CalloutCooldownEntity.patternId must be non-blank" }
    }
}
