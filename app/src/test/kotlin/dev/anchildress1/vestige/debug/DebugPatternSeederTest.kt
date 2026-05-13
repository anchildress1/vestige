package dev.anchildress1.vestige.debug

import androidx.test.core.app.ApplicationProvider
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.storage.MarkdownEntryStore
import dev.anchildress1.vestige.storage.PatternStore
import dev.anchildress1.vestige.storage.VestigeBoxStore
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
@Config(manifest = Config.NONE)
class DebugPatternSeederTest {

    private lateinit var filesDir: File
    private lateinit var boxStore: BoxStore
    private lateinit var entryStore: EntryStore
    private lateinit var patternStore: PatternStore
    private lateinit var markdownStore: MarkdownEntryStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        filesDir = File(context.filesDir, "debug-seed-${System.nanoTime()}").also(File::mkdirs)
        boxStore = VestigeBoxStore.openAt(File(filesDir, "objectbox"))
        markdownStore = MarkdownEntryStore(filesDir)
        entryStore = EntryStore(boxStore, markdownStore)
        patternStore = PatternStore(boxStore)
    }

    @After
    fun tearDown() {
        boxStore.close()
        filesDir.deleteRecursively()
    }

    @Test
    fun `seed writes completed entries and markdown fixtures`() {
        DebugPatternSeeder.seed(filesDir, boxStore, patternStore)

        assertEquals(12L, entryStore.count())
        assertEquals(12L, entryStore.countCompleted())
        assertEquals(12, markdownStore.listAll().size)
        assertEquals(2, patternStore.findVisibleSortedByLastSeen().size)
    }
}
