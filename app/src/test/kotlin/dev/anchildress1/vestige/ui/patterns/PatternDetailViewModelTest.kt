package dev.anchildress1.vestige.ui.patterns

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
import dev.anchildress1.vestige.testing.cleanupObjectBoxTempRoot
import dev.anchildress1.vestige.testing.newInMemoryObjectBoxDirectory
import dev.anchildress1.vestige.testing.newModuleTempRoot
import dev.anchildress1.vestige.testing.openInMemoryBoxStore
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
@Config(
    manifest = Config.NONE,
    application = android.app.Application::class,
)
class PatternDetailViewModelTest {

    private lateinit var tempRoot: File
    private lateinit var dataDir: File
    private lateinit var markdownDir: File
    private lateinit var boxStore: BoxStore
    private lateinit var entryStore: EntryStore
    private lateinit var patternStore: PatternStore
    private lateinit var patternRepo: PatternRepo
    private val testDispatcher = UnconfinedTestDispatcher()
    private val baseClock: Clock = Clock.fixed(Instant.parse("2026-05-12T12:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        tempRoot = newModuleTempRoot("vestige-pattern-detail-viewmodel-")
        dataDir = newInMemoryObjectBoxDirectory("ob-patterns-detail-")
        markdownDir = File(tempRoot, "md-${System.nanoTime()}").apply { mkdirs() }
        boxStore = openInMemoryBoxStore(dataDir)
        entryStore = EntryStore(boxStore, MarkdownEntryStore(markdownDir))
        patternStore = PatternStore(boxStore, baseClock)
        patternRepo = PatternRepo(patternStore, baseClock)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        boxStore.close()
        cleanupObjectBoxTempRoot(tempRoot, dataDir)
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
            assertEquals(PatternState.ACTIVE, loaded.state)
            val sourceTimestamps = loaded.sources.map { it.entryId }
            // Entries seeded with ascending timestamps; sources should be reverse order.
            assertEquals(entries.reversed().map { it.id }, sourceTimestamps)
            assertFalse(loaded.isTerminal)
            assertNull(loaded.terminalLabel)
            assertEquals(
                setOf(PatternAction.DROP, PatternAction.SKIP),
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
    fun `drop updates state to terminal Loaded with the closed-versus-dropped label`() = runTest(testDispatcher) {
        val entries = seedEntries(1)
        seedActivePattern("p-drop-terminal", lastSeenMs = 100L, supporting = entries)
        val vm = newViewModel("p-drop-terminal")
        vm.drop()
        vm.state.test {
            val loaded = expectMostRecentItem() as PatternDetailUiState.Loaded
            assertEquals(PatternState.DROPPED, loaded.state)
            assertTrue(loaded.isTerminal)
            assertNotNull(loaded.terminalLabel)
            assertEquals(
                dev.anchildress1.vestige.R.string.pattern_terminal_dismissed,
                loaded.terminalLabel!!.prefixRes,
            )
        }
        assertEquals(PatternState.DROPPED, patternStore.findByPatternId("p-drop-terminal")?.state)
    }

    @Test
    fun `CLOSED pattern loads as terminal with the resolved label and Restart available`() = runTest(testDispatcher) {
        // CLOSED is model-detected only (v1.5) — seed it directly since no user action makes it.
        val entries = seedEntries(1)
        seedActivePattern("p-closed", lastSeenMs = 100L, supporting = entries)
        patternStore.findByPatternId("p-closed")!!.also {
            it.state = PatternState.CLOSED
            patternStore.put(it)
        }
        val vm = newViewModel("p-closed")
        vm.state.test {
            val loaded = expectMostRecentItem() as PatternDetailUiState.Loaded
            assertEquals(PatternState.CLOSED, loaded.state)
            assertTrue(loaded.isTerminal)
            assertEquals(
                dev.anchildress1.vestige.R.string.pattern_terminal_resolved,
                loaded.terminalLabel!!.prefixRes,
            )
            assertNotNull(loaded.terminalLabel!!.days)
            assertEquals(setOf(PatternAction.RESTART), loaded.availableActions)
        }
    }

    @Test
    fun `drop on an unknown patternId does not crash, refreshes to NotFound, and emits no event`() =
        runTest(testDispatcher) {
            val vm = newViewModel("missing-detail")
            vm.events.test {
                vm.drop()
                // dispatch swallows the missing-row throw; no event for a no-op.
                expectNoEvents()
            }
            vm.state.test {
                assertEquals(PatternDetailUiState.NotFound, expectMostRecentItem())
            }
        }

    @Test
    fun `skip on an unknown patternId does not crash, refreshes to NotFound, and emits no event`() =
        runTest(testDispatcher) {
            val vm = newViewModel("missing-detail")
            vm.events.test {
                vm.skip()
                expectNoEvents()
            }
            vm.state.test {
                assertEquals(PatternDetailUiState.NotFound, expectMostRecentItem())
            }
        }

    @Test
    fun `drop on a row already DROPPED does not crash, emits no event, and stays DROPPED`() = runTest(testDispatcher) {
        val entries = seedEntries(1)
        seedActivePattern("p-already-dropped", lastSeenMs = 100L, supporting = entries)
        patternStore.findByPatternId("p-already-dropped")!!.also {
            it.state = PatternState.DROPPED
            patternStore.put(it)
        }
        val vm = newViewModel("p-already-dropped")
        vm.events.test {
            vm.drop()
            expectNoEvents()
        }
        assertEquals(PatternState.DROPPED, patternStore.findByPatternId("p-already-dropped")?.state)
    }

    @Test
    fun `skip on a non-ACTIVE row does not crash, emits no event, and leaves state unchanged`() =
        runTest(testDispatcher) {
            val entries = seedEntries(1)
            seedActivePattern("p-snoozed", lastSeenMs = 100L, supporting = entries)
            patternStore.findByPatternId("p-snoozed")!!.also {
                it.state = PatternState.SNOOZED
                it.snoozedUntil = baseClock.millis() + 1_000L
                patternStore.put(it)
            }
            val vm = newViewModel("p-snoozed")
            vm.events.test {
                vm.skip()
                expectNoEvents()
            }
            assertEquals(PatternState.SNOOZED, patternStore.findByPatternId("p-snoozed")?.state)
        }

    @Test
    fun `undo on a stale state logs and refreshes without crashing`() = runTest(testDispatcher) {
        val entries = seedEntries(1)
        seedActivePattern("p-stale", lastSeenMs = 100L, supporting = entries)
        val vm = newViewModel("p-stale")
        // Skip then drop in quick succession — state is now DROPPED.
        vm.skip()
        assertEquals(PatternState.SNOOZED, patternStore.findByPatternId("p-stale")?.state)
        vm.drop()
        assertEquals(PatternState.DROPPED, patternStore.findByPatternId("p-stale")?.state)
        // Tap-undo on the older SKIP snackbar — PatternRepo.skip(undo=true) requires SNOOZED,
        // but the row is DROPPED → throws. runCatching must swallow it.
        vm.undo(PatternUndo("p-stale", PatternAction.SKIP))
        // State unchanged; VM didn't crash.
        assertEquals(PatternState.DROPPED, patternStore.findByPatternId("p-stale")?.state)
        vm.state.test {
            val loaded = expectMostRecentItem() as PatternDetailUiState.Loaded
            assertTrue(loaded.isTerminal)
        }
    }

    @Test
    fun `drop emits undo event and undo restores ACTIVE state`() = runTest(testDispatcher) {
        val entries = seedEntries(1)
        seedActivePattern("p-drop-undo", lastSeenMs = 100L, supporting = entries)
        val vm = newViewModel("p-drop-undo")
        vm.events.test {
            vm.drop()
            val event = awaitItem()
            assertEquals(PatternAction.DROP, event.action)
            assertEquals("p-drop-undo", event.patternId)
            assertNotNull(event.undo)
            vm.undo(event.undo!!)
        }
        assertEquals(PatternState.ACTIVE, patternStore.findByPatternId("p-drop-undo")?.state)
    }

    @Test
    fun `drop surfaces a Dropped terminal label`() = runTest(testDispatcher) {
        val entries = seedEntries(1)
        seedActivePattern("p-drop", lastSeenMs = 100L, supporting = entries)
        val vm = newViewModel("p-drop")
        vm.drop()
        vm.state.test {
            val loaded = expectMostRecentItem() as PatternDetailUiState.Loaded
            assertTrue(loaded.isTerminal)
            assertEquals(
                dev.anchildress1.vestige.R.string.pattern_terminal_dismissed,
                loaded.terminalLabel!!.prefixRes,
            )
        }
        assertEquals(PatternState.DROPPED, patternStore.findByPatternId("p-drop")?.state)
    }

    @Test
    fun `skip leaves the detail in a non-terminal Loaded state`() = runTest(testDispatcher) {
        val entries = seedEntries(1)
        seedActivePattern("p-skip", lastSeenMs = 100L, supporting = entries)
        val vm = newViewModel("p-skip")
        vm.skip()
        vm.state.test {
            val loaded = expectMostRecentItem() as PatternDetailUiState.Loaded
            assertEquals(PatternState.SNOOZED, loaded.state)
            assertEquals(false, loaded.isTerminal)
            assertEquals(null, loaded.terminalLabel)
            assertEquals(setOf(PatternAction.RESTART), loaded.availableActions)
        }
        assertEquals(PatternState.SNOOZED, patternStore.findByPatternId("p-skip")?.state)
    }

    @Test
    fun `restart undo from snoozed restores the original snoozedUntil`() = runTest(testDispatcher) {
        val entries = seedEntries(1)
        seedActivePattern("p-restart-snooze-detail", lastSeenMs = 100L, supporting = entries)
        val vm = newViewModel("p-restart-snooze-detail")
        vm.skip()
        val originalSnoozedUntil = patternStore.findByPatternId("p-restart-snooze-detail")?.snoozedUntil
        assertNotNull(originalSnoozedUntil)

        vm.events.test {
            vm.restart()
            val event = awaitItem()
            assertEquals(PatternAction.RESTART, event.action)
            assertEquals(PatternState.SNOOZED, event.undo?.previousState)
            assertEquals(originalSnoozedUntil, event.undo?.previousSnoozedUntil)
            vm.undo(event.undo!!)
        }

        val row = patternStore.findByPatternId("p-restart-snooze-detail")!!
        assertEquals(PatternState.SNOOZED, row.state)
        assertEquals(originalSnoozedUntil, row.snoozedUntil)
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
