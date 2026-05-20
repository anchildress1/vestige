package dev.anchildress1.vestige.debug

import dev.anchildress1.vestige.storage.CalloutCooldownEntity
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.storage.PatternStore
import dev.anchildress1.vestige.storage.closeAfterCleaningThreadResources
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
import java.time.Instant

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

    @Before
    fun setUp() {
        filesDir = newModuleTempRoot("debug-seed-")
        dataDir = newInMemoryObjectBoxDirectory("debug-seed-objectbox-")
        boxStore = openInMemoryBoxStore(dataDir)
        entryStore = EntryStore(boxStore)
        patternStore = PatternStore(boxStore)
    }

    @After
    fun tearDown() {
        boxStore.closeAfterCleaningThreadResources()
        cleanupObjectBoxTempRoot(filesDir, dataDir)
    }

    @Test
    fun `seed writes completed entries and pattern fixtures`() {
        // 12 narrative entries + 23 vocab-drift entries (8 exhaustion + 8 cognitive-fog + 7
        // wired-tired) = 35 total. The vocab pattern joins the 2 narrative patterns ⇒ 3.
        DebugPatternSeeder.seed(filesDir, boxStore, patternStore)

        assertEquals(35L, entryStore.count())
        assertEquals(35L, entryStore.countCompleted())
        assertEquals(3, patternStore.findVisibleSortedByLastSeen().size)
    }

    @Test
    fun `seed writes explicit demo timestamps into objectbox`() {
        DebugPatternSeeder.seed(filesDir, boxStore, patternStore)

        val entries = entryStore.listCompleted()
        assertEquals(
            Instant.parse("2026-05-05T14:10:00Z").toEpochMilli(),
            entries.single { it.markdownFilename == "debug-seed-1.md" }.timestampEpochMs,
        )
        assertEquals(
            Instant.parse("2026-05-19T13:55:00Z").toEpochMilli(),
            entries.single { it.markdownFilename == "debug-seed-8.md" }.timestampEpochMs,
        )
    }

    @Test
    fun `seed is idempotent — re-running produces the same row counts`() {
        DebugPatternSeeder.seed(filesDir, boxStore, patternStore)
        DebugPatternSeeder.seed(filesDir, boxStore, patternStore)

        assertEquals(35L, entryStore.count())
        assertEquals(35L, entryStore.countCompleted())
        assertEquals(3, patternStore.findVisibleSortedByLastSeen().size)
    }

    @Test
    fun `seed clears stale callout cooldown state before rebuilding fixtures`() {
        boxStore.boxFor(CalloutCooldownEntity::class.java).put(
            CalloutCooldownEntity(
                patternId = "stale-pattern-sha",
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
