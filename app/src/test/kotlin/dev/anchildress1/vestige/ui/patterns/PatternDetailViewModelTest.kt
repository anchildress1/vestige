package dev.anchildress1.vestige.ui.patterns

import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.PatternState
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.storage.MarkdownEntryStore
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.PatternRepo
import dev.anchildress1.vestige.storage.PatternStore
import dev.anchildress1.vestige.storage.VestigeBoxStore
import io.objectbox.BoxStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PatternDetailViewModelTest {

    private lateinit var dataDir: File
    private lateinit var boxStore: BoxStore
    private lateinit var entryStore: EntryStore
    private lateinit var patternStore: PatternStore
    private lateinit var patternRepo: PatternRepo
    private val testDispatcher = UnconfinedTestDispatcher()
    private val baseClock: Clock = Clock.fixed(Instant.parse("2026-05-12T12:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        dataDir = File(context.filesDir, "ob-patterns-detail-${System.nanoTime()}")
        boxStore = VestigeBoxStore.openAt(dataDir)
        entryStore = EntryStore(boxStore, MarkdownEntryStore(File(context.filesDir, "md-${System.nanoTime()}")))
        patternStore = PatternStore(boxStore, baseClock)
        patternRepo = PatternRepo(patternStore, baseClock)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        boxStore.close()
        dataDir.deleteRecursively()
    }

    @Test
    fun `NotFound when pattern is missing`() = runTest(testDispatcher) {
        val vm = newViewModel("missing")
        vm.state.test {
            val terminal = expectMostRecentItem()
            assertEquals(PatternDetailUiState.NotFound, terminal)
        }
    }

    @Test
    fun `Loaded surfaces sources sorted newest-first`() = runTest(testDispatcher) {
        val entries = seedEntries(3)
        seedActivePattern("p-detail", lastSeenMs = 500L, supporting = entries)
        val vm = newViewModel("p-detail")
        vm.state.test {
            val loaded = expectMostRecentItem() as PatternDetailUiState.Loaded
            assertEquals("p-detail", loaded.patternId)
            assertEquals(3, loaded.supportingCount)
            assertEquals(3L, loaded.totalEntryCount)
            val sourceTimestamps = loaded.sources.map { it.entryId }
            // Entries seeded with ascending timestamps; sources should be reverse order.
            assertEquals(entries.reversed().map { it.id }, sourceTimestamps)
            assertFalse(loaded.isTerminal)
            assertNull(loaded.terminalLabel)
            assertEquals(
                setOf(
                    PatternAction.DISMISSED,
                    PatternAction.SNOOZED,
                    PatternAction.MARKED_RESOLVED,
                ),
                loaded.availableActions,
            )
        }
    }

    @Test
    fun `totalEntryCount excludes pending entries`() = runTest(testDispatcher) {
        val entries = seedEntries(2)
        seedEntries(1, extractionStatus = ExtractionStatus.PENDING)
        seedActivePattern("p-count", lastSeenMs = 500L, supporting = entries)
        val vm = newViewModel("p-count")
        vm.state.test {
            val loaded = expectMostRecentItem() as PatternDetailUiState.Loaded
            assertEquals(2L, loaded.totalEntryCount)
        }
    }

    @Test
    fun `markResolved updates state to terminal Loaded`() = runTest(testDispatcher) {
        val entries = seedEntries(1)
        seedActivePattern("p-resolve", lastSeenMs = 100L, supporting = entries)
        val vm = newViewModel("p-resolve")
        vm.markResolved()
        vm.state.test {
            val loaded = expectMostRecentItem() as PatternDetailUiState.Loaded
            assertTrue(loaded.isTerminal)
            assertNotNull(loaded.terminalLabel)
            assertTrue(loaded.terminalLabel!!.startsWith("Marked resolved"))
        }
        assertEquals(PatternState.RESOLVED, patternStore.findByPatternId("p-resolve")?.state)
    }

    @Test
    fun `dismiss emits undo event and undo restores ACTIVE state`() = runTest(testDispatcher) {
        val entries = seedEntries(1)
        seedActivePattern("p-dismiss-undo", lastSeenMs = 100L, supporting = entries)
        val vm = newViewModel("p-dismiss-undo")
        vm.events.test {
            vm.dismiss()
            val event = awaitItem()
            assertEquals(PatternAction.DISMISSED, event.action)
            assertEquals("p-dismiss-undo", event.patternId)
            assertNotNull(event.undo)
            vm.undo(event.undo!!)
        }
        assertEquals(PatternState.ACTIVE, patternStore.findByPatternId("p-dismiss-undo")?.state)
    }

    @Test
    fun `dismiss surfaces a Dismissed terminal label`() = runTest(testDispatcher) {
        val entries = seedEntries(1)
        seedActivePattern("p-dismiss", lastSeenMs = 100L, supporting = entries)
        val vm = newViewModel("p-dismiss")
        vm.dismiss()
        vm.state.test {
            val loaded = expectMostRecentItem() as PatternDetailUiState.Loaded
            assertTrue(loaded.isTerminal)
            assertTrue(loaded.terminalLabel!!.startsWith("Dismissed"))
        }
        assertEquals(PatternState.DISMISSED, patternStore.findByPatternId("p-dismiss")?.state)
    }

    @Test
    fun `snooze leaves the detail in a non-terminal Loaded state`() = runTest(testDispatcher) {
        val entries = seedEntries(1)
        seedActivePattern("p-snooze", lastSeenMs = 100L, supporting = entries)
        val vm = newViewModel("p-snooze")
        vm.snooze()
        vm.state.test {
            val loaded = expectMostRecentItem() as PatternDetailUiState.Loaded
            assertEquals(false, loaded.isTerminal)
            assertEquals(null, loaded.terminalLabel)
            assertEquals(setOf(PatternAction.DISMISSED), loaded.availableActions)
        }
        assertEquals(PatternState.SNOOZED, patternStore.findByPatternId("p-snooze")?.state)
    }

    private fun newViewModel(patternId: String) = PatternDetailViewModel(
        patternId = patternId,
        patternStore = patternStore,
        patternRepo = patternRepo,
        entryStore = entryStore,
        ioDispatcher = testDispatcher,
    )

    private fun seedEntries(
        count: Int,
        extractionStatus: ExtractionStatus = ExtractionStatus.COMPLETED,
    ): List<EntryEntity> {
        val box = boxStore.boxFor(EntryEntity::class.java)
        val rows = (0 until count).map { idx ->
            EntryEntity(
                entryText = "entry $idx",
                timestampEpochMs = 1_700_000_000_000L + idx * 60_000L,
                markdownFilename = "${1_700_000_000_000L + idx}--entry-$idx.md",
                extractionStatus = extractionStatus,
            )
        }
        rows.forEach { box.put(it) }
        return rows
    }

    private fun seedActivePattern(patternId: String, lastSeenMs: Long, supporting: List<EntryEntity>) {
        val entity = PatternEntity(
            patternId = patternId,
            kind = PatternKind.TEMPLATE_RECURRENCE,
            signatureJson = "{}",
            title = "Title $patternId",
            templateLabel = "Aftermath",
            firstSeenTimestamp = lastSeenMs - 1_000L,
            lastSeenTimestamp = lastSeenMs,
            state = PatternState.ACTIVE,
            stateChangedTimestamp = lastSeenMs,
            latestCalloutText = "Callout for $patternId",
        )
        boxStore.boxFor(PatternEntity::class.java).put(entity)
        val saved = patternStore.findByPatternId(patternId)
            ?: error("pattern not persisted: $patternId")
        saved.supportingEntries.addAll(supporting)
        boxStore.boxFor(PatternEntity::class.java).put(saved)
    }
}
