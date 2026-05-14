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
    fun `default state permits callout`() {
        assertTrue(store.isCalloutPermitted())
        assertNull(store.snapshot().lastCalloutEntryId)
        assertNull(store.snapshot().pendingCalloutEntryId)
    }

    @Test
    fun `recordFired sets default 3-entry suppression window`() {
        store.recordFired(entryId = 42L, timestampMs = 1_000L)
        assertFalse(store.isCalloutPermitted())
        assertEquals(3, store.snapshot().remainingSuppression)
        assertEquals(42L, store.snapshot().lastCalloutEntryId)
    }

    @Test
    fun `consumeOneEntry counts down to zero then permits again`() {
        store.recordFired(entryId = 42L, timestampMs = 1_000L)
        repeat(3) { store.consumeOneEntry() }
        assertTrue(store.isCalloutPermitted())
        // Extra consume is a no-op.
        store.consumeOneEntry()
        assertEquals(0, store.snapshot().remainingSuppression)
    }

    @Test
    fun `cooldown survives BoxStore restart`() {
        store.recordFired(entryId = 9L, timestampMs = 1_000L)
        store.consumeOneEntry()
        boxStore.close()
        boxStore = openInMemoryBoxStore(dataDir)
        store = CalloutCooldownStore(boxStore)
        assertEquals(2, store.snapshot().remainingSuppression)
        assertEquals(9L, store.snapshot().lastCalloutEntryId)
    }

    @Test
    fun `tryReserveCallout claims the slot until confirmed`() {
        assertEquals(CalloutCooldownStore.ReservationOutcome.RESERVED, store.tryReserveCallout(42L))
        assertFalse(store.isCalloutPermitted())
        assertEquals(42L, store.snapshot().pendingCalloutEntryId)

        assertEquals(
            CalloutCooldownStore.ReservationOutcome.BLOCKED_BY_PENDING_RESERVATION,
            store.tryReserveCallout(84L),
        )
    }

    @Test
    fun `confirmReservedCallout clears reservation and starts suppression`() {
        store.tryReserveCallout(42L)

        store.confirmReservedCallout(entryId = 42L, timestampMs = 1_000L)

        val snapshot = store.snapshot()
        assertNull(snapshot.pendingCalloutEntryId)
        assertEquals(42L, snapshot.lastCalloutEntryId)
        assertEquals(3, snapshot.remainingSuppression)
    }

    @Test
    fun `releaseReservedCallout restores eligibility without starting suppression`() {
        store.tryReserveCallout(42L)

        store.releaseReservedCallout(42L)

        val snapshot = store.snapshot()
        assertTrue(store.isCalloutPermitted())
        assertNull(snapshot.pendingCalloutEntryId)
        assertEquals(0, snapshot.remainingSuppression)
        assertNull(snapshot.lastCalloutEntryId)
    }

    @Test
    fun `suppressed reservation attempt burns one entry and rejects the callout`() {
        store.recordFired(entryId = 42L, timestampMs = 1_000L)

        assertEquals(
            CalloutCooldownStore.ReservationOutcome.SUPPRESSED_BY_COOLDOWN,
            store.tryReserveCallout(84L),
        )
        assertEquals(2, store.snapshot().remainingSuppression)
        assertNull(store.snapshot().pendingCalloutEntryId)
    }

    @Test
    fun `clearStalePendingReservation drops a survived pending across simulated restart`() {
        // Simulate the death-between-reserve-and-settle scenario: process reserves the slot,
        // dies, restarts. Without recovery, every future save sees BLOCKED_BY_PENDING_RESERVATION
        // for the rest of the install.
        store.tryReserveCallout(42L)
        boxStore.close()
        boxStore = openInMemoryBoxStore(dataDir)
        store = CalloutCooldownStore(boxStore)
        assertEquals(42L, store.snapshot().pendingCalloutEntryId)

        store.clearStalePendingReservation()

        assertNull(store.snapshot().pendingCalloutEntryId)
        assertTrue(store.isCalloutPermitted())
    }

    @Test
    fun `clearStalePendingReservation preserves a live suppression window`() {
        store.recordFired(entryId = 42L, timestampMs = 1_000L)
        store.clearStalePendingReservation()
        // remainingSuppression untouched — startup recovery is for pending reservations only,
        // not for the durable window that fired callouts owe their suppression.
        assertEquals(3, store.snapshot().remainingSuppression)
    }
}
