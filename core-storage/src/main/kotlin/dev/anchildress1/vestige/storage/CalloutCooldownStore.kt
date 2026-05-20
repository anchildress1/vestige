package dev.anchildress1.vestige.storage

import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import io.objectbox.query.QueryBuilder

/**
 * Owner of the per-pattern callout cooldown table. One row per `patternId`; tracks fire history,
 * in-flight reservation, and the remaining-suppression counter that gates whether the pattern's
 * callout can fire on subsequent entries.
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
        findByPatternId(patternId) ?: CalloutCooldownEntity(patternId = patternId).also { box.put(it) }
    }

    private fun findByPatternId(patternId: String): CalloutCooldownEntity? = box
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

    /**
     * Read-only eligibility check for [patternId]. Returns `true` when no row exists for the
     * pattern (never fired ⇒ eligible) or when the row's window is clear. Does NOT lazily create
     * a row — the orchestrator's pre-reserve selector relies on this purity so picking a pattern
     * doesn't itself populate the cooldown table.
     */
    fun isCalloutPermitted(patternId: String): Boolean {
        val row = findByPatternId(patternId) ?: return true
        return row.remainingSuppression == 0 && row.pendingCalloutEntryId == null
    }

    /**
     * Atomically claim [patternId]'s callout slot for [entryId]. Returns `RESERVED` on success,
     * `BLOCKED_BY_PENDING_RESERVATION` when another entry already holds this pattern's slot,
     * `SUPPRESSED_BY_COOLDOWN` when this pattern's suppression window is still active. Re-reserving
     * the same `(entryId, patternId)` pair is idempotent and returns `RESERVED`.
     *
     * The orchestrator's selector pre-filters by `isCalloutPermitted` so production callers should
     * never see `SUPPRESSED_BY_COOLDOWN` — but a concurrent `settleReservedCallout` from a parallel
     * save can land a suppression window between the filter read and this reserve write, and the
     * store-side check is the race-safety net that handles that case gracefully.
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
     * Test-only seam: synthesizes a fire on [patternId] without driving the reserve→confirm
     * lifecycle. Production paths use `tryReserveCallout` + `confirmReservedCallout`.
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
     * can pass it as `except` to [decrementAllActive], or `null` when no row holds a pending
     * reservation for [entryId] — a stale or duplicate settle. Caller decides whether `null` is
     * a logic bug (see `PatternDetectionOrchestrator.settleReservedCallout`).
     */
    fun confirmReservedCallout(entryId: Long, timestampMs: Long, windowEntries: Int = DEFAULT_WINDOW): String? {
        require(windowEntries >= 0) { "windowEntries >= 0 required (got $windowEntries)" }
        return boxStore.callInTx<String?> {
            val row = findByPendingEntryIdInTx(entryId) ?: return@callInTx null
            row.lastCalloutEntryId = entryId
            row.lastCalloutTimestamp = timestampMs
            row.remainingSuppression = windowEntries
            row.pendingCalloutEntryId = null
            box.put(row)
            row.patternId
        }
    }

    /**
     * Drop the reservation held by [entryId] (on whichever pattern row owns it). Returns `true`
     * when a pending reservation was found and cleared, `false` when none matched — the caller
     * decides whether `false` is a stale settle (legitimate) or an invariant break.
     */
    fun releaseReservedCallout(entryId: Long): Boolean = boxStore.callInTx<Boolean> {
        val row = findByPendingEntryIdInTx(entryId) ?: return@callInTx false
        row.pendingCalloutEntryId = null
        box.put(row)
        true
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
