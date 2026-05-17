package dev.anchildress1.vestige.debug

import dev.anchildress1.vestige.storage.CalloutCooldownEntity
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.storage.MarkdownEntryStore
import dev.anchildress1.vestige.storage.PatternStore
import dev.anchildress1.vestige.testing.cleanupObjectBoxTempRoot
import dev.anchildress1.vestige.testing.newInMemoryObjectBoxDirectory
import dev.anchildress1.vestige.testing.newModuleTempRoot
import dev.anchildress1.vestige.testing.openInMemoryBoxStore
import io.objectbox.BoxStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
// VestigeApplication.onCreate builds a real AppContainer — we don't need that here since the
// seeder is exercised against hand-built stores. Bare android.app.Application skips the bootstrap.
@Config(manifest = Config.NONE, application = android.app.Application::class)
class DebugPatternSeederTest {

    private lateinit var filesDir: File
    private lateinit var dataDir: File
    private lateinit var boxStore: BoxStore
    private lateinit var entryStore: EntryStore
    private lateinit var patternStore: PatternStore
    private lateinit var markdownStore: MarkdownEntryStore

    @Before
    fun setUp() {
        filesDir = newModuleTempRoot("debug-seed-")
        dataDir = newInMemoryObjectBoxDirectory("debug-seed-objectbox-")
        boxStore = openInMemoryBoxStore(dataDir)
        markdownStore = MarkdownEntryStore(filesDir)
        entryStore = EntryStore(boxStore, markdownStore)
        patternStore = PatternStore(boxStore)
    }

    @After
    fun tearDown() {
        boxStore.close()
        cleanupObjectBoxTempRoot(filesDir, dataDir)
    }

    @Test
    fun `seed writes completed entries and markdown fixtures`() {
        DebugPatternSeeder.seed(filesDir, boxStore, patternStore)

        assertEquals(12L, entryStore.count())
        assertEquals(12L, entryStore.countCompleted())
        assertEquals(12, markdownStore.listAll().size)
        assertEquals(2, patternStore.findVisibleSortedByLastSeen().size)
    }

    @Test
    fun `seed clears stale callout cooldown state before rebuilding fixtures`() {
        boxStore.boxFor(CalloutCooldownEntity::class.java).put(
            CalloutCooldownEntity(
                remainingSuppression = 3,
                pendingCalloutEntryId = 99L,
                lastCalloutEntryId = 42L,
                lastCalloutTimestamp = 1234L,
            ),
        )

        DebugPatternSeeder.seed(filesDir, boxStore, patternStore)

        assertEquals(0, boxStore.boxFor(CalloutCooldownEntity::class.java).count())
    }
}
