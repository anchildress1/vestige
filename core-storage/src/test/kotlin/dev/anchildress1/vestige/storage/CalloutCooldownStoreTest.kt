package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.testing.newInMemoryObjectBoxDirectory
import dev.anchildress1.vestige.testing.openInMemoryBoxStore
import io.objectbox.BoxStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CalloutCooldownStoreTest {

    private lateinit var boxStore: BoxStore
    private lateinit var dataDir: File
    private lateinit var store: CalloutCooldownStore

    @Before
    fun setUp() {
        dataDir = newInMemoryObjectBoxDirectory("objectbox-cooldown-")
        boxStore = openInMemoryBoxStore(dataDir)
        store = CalloutCooldownStore(boxStore)
    }

    @After
    fun tearDown() {
        boxStore.close()
        BoxStore.deleteAllFiles(dataDir)
    }

    @Test
    fun `unknown pattern starts callout-permitted with no recorded fire`() {
        assertTrue(store.isCalloutPermitted(PATTERN_A))
        val snapshot = store.snapshotFor(PATTERN_A)
        assertNull(snapshot.lastCalloutEntryId)
        assertNull(snapshot.pendingCalloutEntryId)
        assertEquals(0, snapshot.remainingSuppression)
    }

    @Test
    fun `recordFired sets default 3-entry suppression on the named pattern only`() {
        store.recordFired(entryId = 42L, patternId = PATTERN_A, timestampMs = 1_000L)

        assertFalse(store.isCalloutPermitted(PATTERN_A))
        assertEquals(3, store.snapshotFor(PATTERN_A).remainingSuppression)
        assertEquals(42L, store.snapshotFor(PATTERN_A).lastCalloutEntryId)
        // Pattern B is untouched — independent counters.
        assertTrue(store.isCalloutPermitted(PATTERN_B))
        assertEquals(0, store.snapshotFor(PATTERN_B).remainingSuppression)
    }

    @Test
    fun `decrementAllActive counts each active pattern's window down to zero`() {
        store.recordFired(entryId = 1L, patternId = PATTERN_A, timestampMs = 1_000L)
        store.recordFired(entryId = 2L, patternId = PATTERN_B, timestampMs = 2_000L)

        repeat(3) { store.decrementAllActive() }

        assertTrue(store.isCalloutPermitted(PATTERN_A))
        assertTrue(store.isCalloutPermitted(PATTERN_B))
        // Idempotent at zero.
        store.decrementAllActive()
        assertEquals(0, store.snapshotFor(PATTERN_A).remainingSuppression)
    }

    @Test
    fun `decrementAllActive with except keeps the just-fired pattern at full window`() {
        store.recordFired(entryId = 1L, patternId = PATTERN_A, timestampMs = 1_000L)
        store.recordFired(entryId = 2L, patternId = PATTERN_B, timestampMs = 2_000L)

        store.decrementAllActive(exceptPatternId = PATTERN_B)

        assertEquals(2, store.snapshotFor(PATTERN_A).remainingSuppression)
        assertEquals(3, store.snapshotFor(PATTERN_B).remainingSuppression)
    }

    @Test
    fun `tryReserveCallout claims the slot on the named pattern only`() {
        assertEquals(
            CalloutCooldownStore.ReservationOutcome.RESERVED,
            store.tryReserveCallout(entryId = 42L, patternId = PATTERN_A),
        )

        assertFalse(store.isCalloutPermitted(PATTERN_A))
        assertEquals(42L, store.snapshotFor(PATTERN_A).pendingCalloutEntryId)
        // Pattern B's slot is independent — still free.
        assertTrue(store.isCalloutPermitted(PATTERN_B))
    }

    @Test
    fun `tryReserveCallout blocks a second entry on the same pattern but not on another`() {
        store.tryReserveCallout(entryId = 42L, patternId = PATTERN_A)

        assertEquals(
            CalloutCooldownStore.ReservationOutcome.BLOCKED_BY_PENDING_RESERVATION,
            store.tryReserveCallout(entryId = 84L, patternId = PATTERN_A),
        )
        // Same entry against a different pattern still succeeds — independence.
        assertEquals(
            CalloutCooldownStore.ReservationOutcome.RESERVED,
            store.tryReserveCallout(entryId = 84L, patternId = PATTERN_B),
        )
    }

    @Test
    fun `tryReserveCallout against a suppressed pattern returns SUPPRESSED_BY_COOLDOWN`() {
        store.recordFired(entryId = 1L, patternId = PATTERN_A, timestampMs = 1_000L)

        // Pattern A is suppressed; orchestrator's filter would normally skip it, but if a
        // caller bypasses the filter the store still rejects without mutating pending state.
        assertEquals(
            CalloutCooldownStore.ReservationOutcome.SUPPRESSED_BY_COOLDOWN,
            store.tryReserveCallout(entryId = 99L, patternId = PATTERN_A),
        )
        assertNull(store.snapshotFor(PATTERN_A).pendingCalloutEntryId)
    }

    @Test
    fun `confirmReservedCallout returns the patternId and starts that pattern's suppression`() {
        store.tryReserveCallout(entryId = 42L, patternId = PATTERN_A)

        val firedPatternId = store.confirmReservedCallout(entryId = 42L, timestampMs = 1_000L)

        assertEquals(PATTERN_A, firedPatternId)
        val snapshot = store.snapshotFor(PATTERN_A)
        assertNull(snapshot.pendingCalloutEntryId)
        assertEquals(42L, snapshot.lastCalloutEntryId)
        assertEquals(3, snapshot.remainingSuppression)
    }

    @Test
    fun `confirmReservedCallout returns null when no pending reservation matches the entry`() {
        // Stale / duplicate settle is a recoverable condition surfaced to the caller via null,
        // not via throw — the orchestrator logs and decrements everything.
        assertNull(store.confirmReservedCallout(entryId = 999L, timestampMs = 1_000L))
    }

    @Test
    fun `releaseReservedCallout clears the pattern's pending without starting suppression`() {
        store.tryReserveCallout(entryId = 42L, patternId = PATTERN_A)

        store.releaseReservedCallout(entryId = 42L)

        val snapshot = store.snapshotFor(PATTERN_A)
        assertTrue(store.isCalloutPermitted(PATTERN_A))
        assertNull(snapshot.pendingCalloutEntryId)
        assertEquals(0, snapshot.remainingSuppression)
        assertNull(snapshot.lastCalloutEntryId)
    }

    @Test
    fun `releaseReservedCallout returns false when no pending reservation matches`() {
        assertFalse(store.releaseReservedCallout(entryId = 999L))
        // No rows materialised for an unknown entry.
        assertTrue(store.isCalloutPermitted(PATTERN_A))
    }

    @Test
    fun `releaseReservedCallout returns true when a pending reservation is cleared`() {
        store.tryReserveCallout(entryId = 42L, patternId = PATTERN_A)
        assertTrue(store.releaseReservedCallout(entryId = 42L))
        assertTrue(store.isCalloutPermitted(PATTERN_A))
    }

    @Test
    fun `clearStalePendingReservation drops survived pending on every pattern row`() {
        // Simulate the death-between-reserve-and-settle scenario across two patterns: both
        // reserved, process dies, restarts. Without recovery, every future save on either
        // pattern sees BLOCKED_BY_PENDING_RESERVATION for the rest of the install.
        store.tryReserveCallout(entryId = 42L, patternId = PATTERN_A)
        store.tryReserveCallout(entryId = 84L, patternId = PATTERN_B)
        boxStore.close()
        boxStore = openInMemoryBoxStore(dataDir)
        store = CalloutCooldownStore(boxStore)
        assertEquals(42L, store.snapshotFor(PATTERN_A).pendingCalloutEntryId)
        assertEquals(84L, store.snapshotFor(PATTERN_B).pendingCalloutEntryId)

        store.clearStalePendingReservation()

        assertNull(store.snapshotFor(PATTERN_A).pendingCalloutEntryId)
        assertNull(store.snapshotFor(PATTERN_B).pendingCalloutEntryId)
        assertTrue(store.isCalloutPermitted(PATTERN_A))
        assertTrue(store.isCalloutPermitted(PATTERN_B))
    }

    @Test
    fun `clearStalePendingReservation preserves live suppression windows`() {
        store.recordFired(entryId = 42L, patternId = PATTERN_A, timestampMs = 1_000L)
        store.clearStalePendingReservation()
        // Window untouched — startup recovery is for pending reservations only, not for the
        // durable window that fired callouts owe their suppression.
        assertEquals(3, store.snapshotFor(PATTERN_A).remainingSuppression)
    }

    @Test
    fun `three decrements after a fire bring the pattern back to eligibility`() {
        // The Phase 3 cooldown contract: callout fires on E, next 3 entries suppress, 4th eligible.
        store.recordFired(entryId = 1L, patternId = PATTERN_A, timestampMs = 1_000L)

        store.decrementAllActive() // E2: 3 → 2
        assertFalse(store.isCalloutPermitted(PATTERN_A))
        store.decrementAllActive() // E3: 2 → 1
        assertFalse(store.isCalloutPermitted(PATTERN_A))
        store.decrementAllActive() // E4: 1 → 0
        assertTrue(store.isCalloutPermitted(PATTERN_A))
    }

    @Test
    fun `state survives BoxStore close and reopen by name`() {
        store.recordFired(entryId = 9L, patternId = PATTERN_A, timestampMs = 1_000L)
        store.decrementAllActive()
        boxStore.close()
        // Reopening the same `memory:` URI reattaches to ObjectBox's in-process registry — that's
        // process-local idempotency, not on-disk durability. Disk persistence is covered by the
        // production wiring's open(Context) test.
        boxStore = openInMemoryBoxStore(dataDir)
        store = CalloutCooldownStore(boxStore)

        assertEquals(2, store.snapshotFor(PATTERN_A).remainingSuppression)
        assertEquals(9L, store.snapshotFor(PATTERN_A).lastCalloutEntryId)
    }

    @Test
    fun `decrementAllActive is a no-op on an empty table`() {
        // No rows created — no NPE, no exception, no spurious puts.
        store.decrementAllActive()
        store.decrementAllActive(exceptPatternId = PATTERN_A)
        assertTrue(store.isCalloutPermitted(PATTERN_A))
    }

    @Test
    fun `decrementAllActive with except naming a row that does not exist still sweeps everyone else`() {
        store.recordFired(entryId = 1L, patternId = PATTERN_A, timestampMs = 1_000L)

        store.decrementAllActive(exceptPatternId = "no-such-pattern")

        assertEquals(2, store.snapshotFor(PATTERN_A).remainingSuppression)
    }

    @Test
    fun `decrementAllActive does not underflow patterns already at zero`() {
        // A at 0 (never fired), B at 3 (just fired). Decrement once.
        store.recordFired(entryId = 1L, patternId = PATTERN_B, timestampMs = 1_000L)

        store.decrementAllActive()

        assertEquals(0, store.snapshotFor(PATTERN_A).remainingSuppression)
        assertEquals(2, store.snapshotFor(PATTERN_B).remainingSuppression)
    }

    @Test
    fun `confirmReservedCallout finds and confirms the right pattern when multiple have pending reservations`() {
        store.tryReserveCallout(entryId = 42L, patternId = PATTERN_A)
        store.tryReserveCallout(entryId = 84L, patternId = PATTERN_B)

        val firedPatternId = store.confirmReservedCallout(entryId = 84L, timestampMs = 2_000L)

        assertEquals(PATTERN_B, firedPatternId)
        // A's pending reservation must be untouched.
        assertEquals(42L, store.snapshotFor(PATTERN_A).pendingCalloutEntryId)
        assertEquals(0, store.snapshotFor(PATTERN_A).remainingSuppression)
        // B's row reflects the fire.
        assertNull(store.snapshotFor(PATTERN_B).pendingCalloutEntryId)
        assertEquals(3, store.snapshotFor(PATTERN_B).remainingSuppression)
    }

    @Test
    fun `releaseReservedCallout clears only the matching pattern's pending`() {
        store.tryReserveCallout(entryId = 42L, patternId = PATTERN_A)
        store.tryReserveCallout(entryId = 84L, patternId = PATTERN_B)

        assertTrue(store.releaseReservedCallout(entryId = 42L))

        assertNull(store.snapshotFor(PATTERN_A).pendingCalloutEntryId)
        // B's reservation untouched.
        assertEquals(84L, store.snapshotFor(PATTERN_B).pendingCalloutEntryId)
    }

    @Test
    fun `re-reserving the same entry-pattern pair is idempotent`() {
        assertEquals(
            CalloutCooldownStore.ReservationOutcome.RESERVED,
            store.tryReserveCallout(entryId = 42L, patternId = PATTERN_A),
        )
        // Second call returns RESERVED without flipping state into a wedge.
        assertEquals(
            CalloutCooldownStore.ReservationOutcome.RESERVED,
            store.tryReserveCallout(entryId = 42L, patternId = PATTERN_A),
        )
        assertEquals(42L, store.snapshotFor(PATTERN_A).pendingCalloutEntryId)
    }

    private companion object {
        const val PATTERN_A = "pattern-a-sha"
        const val PATTERN_B = "pattern-b-sha"
    }
}
