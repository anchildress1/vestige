package dev.anchildress1.vestige.debug

import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.storage.CalloutCooldownEntity
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.closeAfterCleaningThreadResources
import dev.anchildress1.vestige.testing.cleanupObjectBoxTempRoot
import dev.anchildress1.vestige.testing.newInMemoryObjectBoxDirectory
import dev.anchildress1.vestige.testing.newModuleTempRoot
import dev.anchildress1.vestige.testing.openInMemoryBoxStore
import io.objectbox.BoxStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class DebugPatternSeederTest {

    private lateinit var tempRoot: File
    private lateinit var dataDir: File
    private lateinit var boxStore: BoxStore
    private lateinit var entryStore: EntryStore

    @Before
    fun setUp() {
        tempRoot = newModuleTempRoot("debug-seed-")
        dataDir = newInMemoryObjectBoxDirectory("debug-seed-objectbox-")
        boxStore = openInMemoryBoxStore(dataDir)
        entryStore = EntryStore(boxStore)
    }

    @After
    fun tearDown() {
        boxStore.closeAfterCleaningThreadResources()
        cleanupObjectBoxTempRoot(tempRoot, dataDir)
    }

    @Test
    fun `seed writes the active corpus as pending entries`() {
        DebugPatternSeeder.seed(boxStore)

        assertEquals(DebugPatternSeeder.ACTIVE_SEED_COUNT.toLong(), entryStore.count())
        val entries = boxStore.boxFor(EntryEntity::class.java).all
        assertTrue(entries.all { it.extractionStatus == ExtractionStatus.PENDING })
    }

    @Test
    fun `seed writes no patterns — pipeline owns detection`() {
        DebugPatternSeeder.seed(boxStore)

        assertEquals(0L, boxStore.boxFor(PatternEntity::class.java).count())
    }

    @Test
    fun `seed is idempotent — re-running produces the same row counts`() {
        DebugPatternSeeder.seed(boxStore)
        DebugPatternSeeder.seed(boxStore)

        assertEquals(DebugPatternSeeder.ACTIVE_SEED_COUNT.toLong(), entryStore.count())
    }

    @Test
    fun `seed clears stale callout cooldown state`() {
        boxStore.boxFor(CalloutCooldownEntity::class.java).put(
            CalloutCooldownEntity(
                patternId = "stale-pattern-sha",
                remainingSuppression = 3,
                pendingCalloutEntryId = 99L,
                lastCalloutEntryId = 42L,
                lastCalloutTimestamp = 1234L,
            ),
        )

        DebugPatternSeeder.seed(boxStore)

        assertEquals(0, boxStore.boxFor(CalloutCooldownEntity::class.java).count())
    }
}
