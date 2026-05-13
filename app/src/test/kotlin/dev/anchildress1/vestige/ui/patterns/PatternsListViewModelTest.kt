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
class PatternsListViewModelTest {

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
        val tempRoot = File(System.getProperty("java.io.tmpdir"), "vestige-patterns-list-viewmodel-tests").apply {
            mkdirs()
        }
        dataDir = File(tempRoot, "ob-patterns-list-${System.nanoTime()}").apply { mkdirs() }
        boxStore = VestigeBoxStore.openAt(dataDir)
        entryStore = EntryStore(
            boxStore,
            MarkdownEntryStore(File(tempRoot, "md-${System.nanoTime()}").apply { mkdirs() }),
        )
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
    fun `empty state when no entries and no patterns`() = runTest(testDispatcher) {
        val vm = newViewModel()
        vm.state.test {
            // Initial Loading may be skipped under the unconfined dispatcher; assert terminal state.
            val terminal = expectMostRecentItem()
            assertEquals(
                PatternsListUiState.Empty(PatternsListUiState.EmptyReason.NO_ENTRIES),
                terminal,
            )
        }
    }

    @Test
    fun `empty state distinguishes entries-without-patterns`() = runTest(testDispatcher) {
        seedEntries(2)
        val vm = newViewModel()
        vm.state.test {
            val terminal = expectMostRecentItem()
            assertEquals(
                PatternsListUiState.Empty(PatternsListUiState.EmptyReason.NO_PATTERNS),
                terminal,
            )
        }
    }

    @Test
    fun `pending entries do not count toward the empty-state denominator`() = runTest(testDispatcher) {
        seedEntries(1, extractionStatus = ExtractionStatus.PENDING)
        val vm = newViewModel()
        vm.state.test {
            val terminal = expectMostRecentItem()
            assertEquals(
                PatternsListUiState.Empty(PatternsListUiState.EmptyReason.NO_ENTRIES),
                terminal,
            )
        }
    }

    @Test
    fun `loaded list sorts by last-seen descending and tags cards with their section`() = runTest(testDispatcher) {
        val entries = seedEntries(3)
        seedActivePattern("older", lastSeenMs = 100L, supporting = listOf(entries[0]))
        seedActivePattern("newer", lastSeenMs = 500L, supporting = listOf(entries[1], entries[2]))
        val vm = newViewModel()
        vm.state.test {
            val loaded = expectMostRecentItem() as PatternsListUiState.Loaded
            assertEquals(listOf("newer", "older"), loaded.cards.map { it.patternId })
            assertEquals(3L, loaded.cards.first().totalEntryCount)
            assertEquals(2, loaded.cards.first().supportingCount)
            assertTrue(loaded.cards.all { it.section == PatternSection.ACTIVE })
            assertEquals(
                setOf(PatternAction.DISMISSED, PatternAction.SNOOZED),
                loaded.cards.first().availableActions,
            )
        }
    }

    @Test
    fun `dismissed and snoozed patterns surface in their own sections`() = runTest(testDispatcher) {
        val entries = seedEntries(3)
        seedActivePattern("dismissed-one", lastSeenMs = 100L, supporting = listOf(entries[0]))
        seedActivePattern("snoozed-one", lastSeenMs = 200L, supporting = listOf(entries[1]))
        seedActivePattern("active-one", lastSeenMs = 300L, supporting = listOf(entries[2]))
        patternRepo.dismiss("dismissed-one")
        patternRepo.snooze("snoozed-one")
        val vm = newViewModel()
        vm.state.test {
            val loaded = expectMostRecentItem() as PatternsListUiState.Loaded
            val bySection = loaded.cards.groupBy { it.section }
            assertEquals(listOf("active-one"), bySection[PatternSection.ACTIVE]!!.map { it.patternId })
            assertEquals(listOf("snoozed-one"), bySection[PatternSection.SNOOZED]!!.map { it.patternId })
            assertEquals(listOf("dismissed-one"), bySection[PatternSection.DISMISSED]!!.map { it.patternId })
            assertEquals(
                setOf(PatternAction.RESTART),
                bySection[PatternSection.SNOOZED]!!.single().availableActions,
            )
            assertEquals(
                setOf(PatternAction.RESTART),
                bySection[PatternSection.DISMISSED]!!.single().availableActions,
            )
        }
    }

    @Test
    fun `dismiss transitions pattern and emits undo event`() = runTest(testDispatcher) {
        val entries = seedEntries(1)
        seedActivePattern("p1", lastSeenMs = 100L, supporting = entries)
        val vm = newViewModel()
        vm.events.test {
            vm.dismiss("p1")
            val event = awaitItem()
            assertEquals(PatternAction.DISMISSED, event.action)
            assertNotNull(event.undo)
        }
        assertEquals(PatternState.DISMISSED, patternStore.findByPatternId("p1")?.state)
    }

    @Test
    fun `markResolved emits a terminal event with no undo`() = runTest(testDispatcher) {
        val entries = seedEntries(1)
        seedActivePattern("p2", lastSeenMs = 100L, supporting = entries)
        val vm = newViewModel()
        vm.events.test {
            vm.markResolved("p2")
            val event = awaitItem()
            assertEquals(PatternAction.MARKED_RESOLVED, event.action)
            assertNull(event.undo)
        }
        assertEquals(PatternState.RESOLVED, patternStore.findByPatternId("p2")?.state)
    }

    @Test
    fun `undo on MARKED_RESOLVED is a no-op since the state is sticky-terminal`() = runTest(testDispatcher) {
        val entries = seedEntries(1)
        seedActivePattern("p-noop", lastSeenMs = 100L, supporting = entries)
        val vm = newViewModel()
        vm.markResolved("p-noop")
        assertEquals(PatternState.RESOLVED, patternStore.findByPatternId("p-noop")?.state)
        // Issuing the MARKED_RESOLVED undo path hits the `Unit` branch — state must not change.
        vm.undo(PatternUndo("p-noop", PatternAction.MARKED_RESOLVED))
        assertEquals(PatternState.RESOLVED, patternStore.findByPatternId("p-noop")?.state)
    }

    @Test
    fun `undo on a stale state logs and refreshes without crashing`() = runTest(testDispatcher) {
        val entries = seedEntries(1)
        seedActivePattern("p-stale-list", lastSeenMs = 100L, supporting = entries)
        val vm = newViewModel()
        vm.snooze("p-stale-list")
        assertEquals(PatternState.SNOOZED, patternStore.findByPatternId("p-stale-list")?.state)
        vm.dismiss("p-stale-list")
        assertEquals(PatternState.DISMISSED, patternStore.findByPatternId("p-stale-list")?.state)
        // Snackbar callback fires SNOOZED-undo against a now-DISMISSED row — PatternRepo throws.
        vm.undo(PatternUndo("p-stale-list", PatternAction.SNOOZED))
        assertEquals(PatternState.DISMISSED, patternStore.findByPatternId("p-stale-list")?.state)
        vm.state.test {
            val loaded = expectMostRecentItem() as PatternsListUiState.Loaded
            assertTrue(loaded.cards.any { it.patternId == "p-stale-list" && it.section == PatternSection.DISMISSED })
        }
    }

    @Test
    fun `undo restores a dismissed pattern back to ACTIVE`() = runTest(testDispatcher) {
        val entries = seedEntries(1)
        seedActivePattern("p3", lastSeenMs = 100L, supporting = entries)
        val vm = newViewModel()
        vm.dismiss("p3")
        assertEquals(PatternState.DISMISSED, patternStore.findByPatternId("p3")?.state)
        vm.undo(PatternUndo("p3", PatternAction.DISMISSED))
        assertEquals(PatternState.ACTIVE, patternStore.findByPatternId("p3")?.state)
        vm.state.test {
            val loaded = expectMostRecentItem() as PatternsListUiState.Loaded
            assertTrue(loaded.cards.any { it.patternId == "p3" })
        }
    }

    @Test
    fun `restart undo from snoozed restores the original snoozedUntil`() = runTest(testDispatcher) {
        val entries = seedEntries(1)
        seedActivePattern("p-restart-snooze-list", lastSeenMs = 100L, supporting = entries)
        patternRepo.snooze("p-restart-snooze-list")
        val originalSnoozedUntil = patternStore.findByPatternId("p-restart-snooze-list")?.snoozedUntil
        assertNotNull(originalSnoozedUntil)

        val vm = newViewModel()
        vm.events.test {
            vm.restart("p-restart-snooze-list")
            val event = awaitItem()
            assertEquals(PatternAction.RESTART, event.action)
            assertEquals(PatternState.SNOOZED, event.undo?.previousState)
            assertEquals(originalSnoozedUntil, event.undo?.previousSnoozedUntil)
            vm.undo(event.undo!!)
        }

        val row = patternStore.findByPatternId("p-restart-snooze-list")!!
        assertEquals(PatternState.SNOOZED, row.state)
        assertEquals(originalSnoozedUntil, row.snoozedUntil)
    }

    private fun newViewModel() = PatternsListViewModel(
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
