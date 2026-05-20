package dev.anchildress1.vestige.storage

import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import io.objectbox.query.QueryBuilder

/**
 * Owner of the per-pattern callout cooldown table per ADR-016. One row per `patternId`; the row
 * tracks fire history, in-flight reservation, and suppression counter. Replaces ADR-003's
 * singleton model — see ADR-016 for the rationale on the global→per-pattern flip.
 */
class CalloutCooldownStore(private val boxStore: BoxStore) {

    private val box get() = boxStore.boxFor<CalloutCooldownEntity>()

    /**
     * Row for [patternId], creating it on first access. Two concurrent callers must never observe
     * "no row" simultaneously and both insert — that would orphan one row and let subsequent
     * reads/writes pick either at random. The check-and-create is wrapped in a tx; the second
     * caller's tx starts after the first's commit and finds the row.
     */
    fun snapshotFor(patternId: String): CalloutCooldownEntity = boxStore.callInTx<CalloutCooldownEntity> {
        findByPatternIdInTx(patternId) ?: CalloutCooldownEntity(patternId = patternId).also { box.put(it) }
    }

    private fun findByPatternIdInTx(patternId: String): CalloutCooldownEntity? = box
        .query()
        .equal(CalloutCooldownEntity_.patternId, patternId, QueryBuilder.StringOrder.CASE_SENSITIVE)
        .build()
        .use { it.findFirst() }

    /**
     * Clear any pending reservation that never resolved across every pattern's row. Called once at
     * process startup — `pendingCalloutEntryId` is durable across restarts and a process death
     * between `tryReserveCallout` and `settleReservedCallout` would otherwise wedge that pattern's
     * future saves with `BLOCKED_BY_PENDING_RESERVATION` permanently. Any in-flight reservation is
     * definitionally stale once the process restarts.
     */
    fun clearStalePendingReservation() {
        boxStore.runInTx {
            box.all.forEach { row ->
                if (row.pendingCalloutEntryId != null) {
                    row.pendingCalloutEntryId = null
                    box.put(row)
                }
            }
        }
    }

    /** True when [patternId] is callout-eligible on the next entry (no suppression, no pending). */
    fun isCalloutPermitted(patternId: String): Boolean = snapshotFor(patternId).let { row ->
        row.remainingSuppression == 0 && row.pendingCalloutEntryId == null
    }

    /**
     * Atomically claim [patternId]'s callout slot for [entryId].
     *
     * - If another entry already holds [patternId]'s slot, this entry is rejected without mutating
     *   state.
     * - Otherwise this entry becomes the sole pending reservation on [patternId]'s row until
     *   confirm/release.
     *
     * Cooldown suppression is enforced upstream by the orchestrator's `isCalloutPermitted` filter
     * at pattern selection — by the time we get here, the pattern is eligible. Concurrent
     * suppression-then-reserve cannot collide because both run inside the same `runInTx` in the
     * orchestrator's `onEntryCommitted`.
     */
    fun tryReserveCallout(entryId: Long, patternId: String): ReservationOutcome = boxStore.callInTx {
        val row = snapshotFor(patternId)
        when {
            row.pendingCalloutEntryId == entryId -> ReservationOutcome.RESERVED

            row.pendingCalloutEntryId != null -> ReservationOutcome.BLOCKED_BY_PENDING_RESERVATION

            row.remainingSuppression > 0 -> ReservationOutcome.SUPPRESSED_BY_COOLDOWN

            else -> {
                row.pendingCalloutEntryId = entryId
                box.put(row)
                ReservationOutcome.RESERVED
            }
        }
    }

    /**
     * Record a fired callout on [patternId]. Suppresses the next [windowEntries] entries on that
     * pattern. Used for direct fires that didn't go through reserve/confirm (tests, future paths).
     */
    fun recordFired(entryId: Long, patternId: String, timestampMs: Long, windowEntries: Int = DEFAULT_WINDOW) {
        require(windowEntries >= 0) { "windowEntries >= 0 required (got $windowEntries)" }
        boxStore.runInTx {
            val row = snapshotFor(patternId)
            row.lastCalloutEntryId = entryId
            row.lastCalloutTimestamp = timestampMs
            row.remainingSuppression = windowEntries
            row.pendingCalloutEntryId = null
            box.put(row)
        }
    }

    /**
     * Convert a previously reserved slot into a durable cooldown window on whichever pattern's row
     * holds [entryId] as its pending reservation. Returns the confirmed `patternId` so the caller
     * can pass it as `except` to [decrementAllActive] without re-scanning.
     */
    fun confirmReservedCallout(
        entryId: Long,
        timestampMs: Long,
        windowEntries: Int = DEFAULT_WINDOW,
    ): String {
        require(windowEntries >= 0) { "windowEntries >= 0 required (got $windowEntries)" }
        return boxStore.callInTx<String> {
            val row = findByPendingEntryIdInTx(entryId)
                ?: error("No pending callout reservation for entry id=$entryId")
            row.lastCalloutEntryId = entryId
            row.lastCalloutTimestamp = timestampMs
            row.remainingSuppression = windowEntries
            row.pendingCalloutEntryId = null
            box.put(row)
            row.patternId
        }
    }

    /** Drop the reservation held by [entryId] (on whichever pattern row owns it). */
    fun releaseReservedCallout(entryId: Long) {
        boxStore.runInTx {
            val row = findByPendingEntryIdInTx(entryId) ?: return@runInTx
            row.pendingCalloutEntryId = null
            box.put(row)
        }
    }

    private fun findByPendingEntryIdInTx(entryId: Long): CalloutCooldownEntity? = box
        .query()
        .equal(CalloutCooldownEntity_.pendingCalloutEntryId, entryId)
        .build()
        .use { it.findFirst() }

    /**
     * Decrement `remainingSuppression` by 1 on every row whose counter > 0, except the optional
     * [exceptPatternId]. Called once per committed entry — the just-fired pattern is excepted so
     * its freshly-set window isn't immediately burned by this entry. Idempotent at 0.
     */
    fun decrementAllActive(exceptPatternId: String? = null) {
        boxStore.runInTx {
            box.all.forEach { row ->
                if (row.remainingSuppression > 0 && row.patternId != exceptPatternId) {
                    row.remainingSuppression -= 1
                    box.put(row)
                }
            }
        }
    }

    companion object {
        /** Default suppression window per Phase 3: 3 entries after a callout fires. */
        const val DEFAULT_WINDOW: Int = 3
    }

    enum class ReservationOutcome {
        RESERVED,
        SUPPRESSED_BY_COOLDOWN,
        BLOCKED_BY_PENDING_RESERVATION,
    }
}
