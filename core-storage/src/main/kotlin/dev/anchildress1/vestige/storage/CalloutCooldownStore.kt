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

    /**
     * Singleton accessor. Two concurrent callers must never observe "no row" simultaneously and
     * both insert — that would orphan one row and let subsequent reads/writes pick either at
     * random. Wrap the check-and-create in a tx so insertion is atomic; the second caller's tx
     * starts after the first's commit and finds the row.
     */
    fun snapshot(): CalloutCooldownEntity = boxStore.callInTx<CalloutCooldownEntity> {
        box.all.firstOrNull() ?: CalloutCooldownEntity().also { box.put(it) }
    }

    /**
     * Clear any pending reservation that never resolved. Called once at process startup —
     * `pendingCalloutEntryId` is durable across restarts and a process death between
     * `tryReserveCallout` and `settleReservedCallout` would otherwise wedge every future save
     * with `BLOCKED_BY_PENDING_RESERVATION` permanently. Any in-flight reservation is
     * definitionally stale once the process restarts.
     */
    fun clearStalePendingReservation() {
        boxStore.runInTx {
            val current = snapshot()
            if (current.pendingCalloutEntryId == null) return@runInTx
            current.pendingCalloutEntryId = null
            box.put(current)
        }
    }

    /** True when a callout is permitted on the next entry (no suppression remaining). */
    fun isCalloutPermitted(): Boolean = snapshot().let { current ->
        current.remainingSuppression == 0 && current.pendingCalloutEntryId == null
    }

    /**
     * Atomically claim the single global callout slot for [entryId].
     *
     * - If the cooldown window is active, this entry spends one suppressed slot and is rejected.
     * - If another entry already holds the slot, this entry is rejected without mutating state.
     * - Otherwise this entry becomes the sole pending reservation until confirm/release.
     */
    fun tryReserveCallout(entryId: Long): ReservationOutcome = boxStore.callInTx {
        val current = snapshot()
        when {
            current.pendingCalloutEntryId == entryId -> ReservationOutcome.RESERVED

            current.pendingCalloutEntryId != null -> ReservationOutcome.BLOCKED_BY_PENDING_RESERVATION

            current.remainingSuppression > 0 -> {
                current.remainingSuppression -= 1
                box.put(current)
                ReservationOutcome.SUPPRESSED_BY_COOLDOWN
            }

            else -> {
                current.pendingCalloutEntryId = entryId
                box.put(current)
                ReservationOutcome.RESERVED
            }
        }
    }

    /** Record a fired callout. Suppresses the next [windowEntries] entries. */
    fun recordFired(entryId: Long, timestampMs: Long, windowEntries: Int = DEFAULT_WINDOW) {
        require(windowEntries >= 0) { "windowEntries >= 0 required (got $windowEntries)" }
        val current = snapshot()
        current.lastCalloutEntryId = entryId
        current.lastCalloutTimestamp = timestampMs
        current.remainingSuppression = windowEntries
        current.pendingCalloutEntryId = null
        box.put(current)
    }

    /** Convert a previously reserved slot into a durable cooldown window. */
    fun confirmReservedCallout(entryId: Long, timestampMs: Long, windowEntries: Int = DEFAULT_WINDOW) {
        require(windowEntries >= 0) { "windowEntries >= 0 required (got $windowEntries)" }
        boxStore.runInTx {
            val current = snapshot()
            check(current.pendingCalloutEntryId == entryId) {
                "No pending callout reservation for entry id=$entryId"
            }
            current.lastCalloutEntryId = entryId
            current.lastCalloutTimestamp = timestampMs
            current.remainingSuppression = windowEntries
            current.pendingCalloutEntryId = null
            box.put(current)
        }
    }

    /** Drop a reservation when the callout never becomes user-visible. */
    fun releaseReservedCallout(entryId: Long) {
        boxStore.runInTx {
            val current = snapshot()
            if (current.pendingCalloutEntryId != entryId) return@runInTx
            current.pendingCalloutEntryId = null
            box.put(current)
        }
    }

    /** Decrement the counter after a non-callout entry. Idempotent at zero. */
    fun consumeOneEntry() {
        boxStore.runInTx {
            val current = snapshot()
            if (current.remainingSuppression == 0) return@runInTx
            current.remainingSuppression -= 1
            box.put(current)
        }
    }

    companion object {
        /** ADR-003 default: suppress callouts on the next 3 entries after one fires. */
        const val DEFAULT_WINDOW: Int = 3
    }

    enum class ReservationOutcome {
        RESERVED,
        SUPPRESSED_BY_COOLDOWN,
        BLOCKED_BY_PENDING_RESERVATION,
    }
}
