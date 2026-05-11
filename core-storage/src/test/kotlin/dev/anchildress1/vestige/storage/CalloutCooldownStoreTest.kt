package dev.anchildress1.vestige.storage

import androidx.test.core.app.ApplicationProvider
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
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        dataDir = File(context.filesDir, "objectbox-cooldown-${System.nanoTime()}")
        boxStore = VestigeBoxStore.openAt(dataDir)
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
        boxStore = VestigeBoxStore.openAt(dataDir)
        store = CalloutCooldownStore(boxStore)
        assertEquals(2, store.snapshot().remainingSuppression)
        assertEquals(9L, store.snapshot().lastCalloutEntryId)
    }
}
