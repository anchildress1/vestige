package dev.anchildress1.vestige.storage

import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor

/**
 * Owner of the singleton callout-cooldown row. ADR-003 §"Cooldown" pins the suppression window to
 * three entries after a callout fires; this store keeps the counter durable across restart so a
 * background-extraction failure cannot lose the position.
 */
class CalloutCooldownStore(private val boxStore: BoxStore) {

    private val box get() = boxStore.boxFor<CalloutCooldownEntity>()

    fun snapshot(): CalloutCooldownEntity = box.all.firstOrNull()
        ?: CalloutCooldownEntity().also { box.put(it) }

    /** True when a callout is permitted on the next entry (no suppression remaining). */
    fun isCalloutPermitted(): Boolean = snapshot().remainingSuppression == 0

    /** Record a fired callout. Suppresses the next [windowEntries] entries. */
    fun recordFired(entryId: Long, timestampMs: Long, windowEntries: Int = DEFAULT_WINDOW) {
        require(windowEntries >= 0) { "windowEntries >= 0 required (got $windowEntries)" }
        val current = snapshot()
        current.lastCalloutEntryId = entryId
        current.lastCalloutTimestamp = timestampMs
        current.remainingSuppression = windowEntries
        box.put(current)
    }

    /** Decrement the counter after a non-callout entry. Idempotent at zero. */
    fun consumeOneEntry() {
        val current = snapshot()
        if (current.remainingSuppression == 0) return
        current.remainingSuppression -= 1
        box.put(current)
    }

    companion object {
        /** ADR-003 default: suppress callouts on the next 3 entries after one fires. */
        const val DEFAULT_WINDOW: Int = 3
    }
}
