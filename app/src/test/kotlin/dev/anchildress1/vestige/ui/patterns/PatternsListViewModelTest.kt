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
@Config(manifest = Config.NONE, application = PatternsTestApplication::class)
class PatternsListViewModelTest {

    private lateinit var tempRoot: File
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
        tempRoot = newModuleTempRoot("vestige-patterns-list-viewmodel-")
        dataDir = newInMemoryObjectBoxDirectory("ob-patterns-list-")
        boxStore = openInMemoryBoxStore(dataDir)
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
        cleanupObjectBoxTempRoot(tempRoot, dataDir)
    }

    @Test
    fun `empty state is NO_ENTRIES with a zero count when there are no entries`() = runTest(testDispatcher) {
        val vm = newViewModel()
        vm.state.test {
            // Initial Loading may be skipped under the unconfined dispatcher; assert terminal state.
            val terminal = expectMostRecentItem()
            assertEquals(
                PatternsListUiState.Empty(PatternsListUiState.EmptyReason.NO_ENTRIES, 0),
                terminal,
            )
        }
    }

    @Test
    fun `empty state stays NO_ENTRIES at nine completed entries (one below the threshold)`() = runTest(testDispatcher) {
        seedEntries(9)
        val vm = newViewModel()
        vm.state.test {
            val terminal = expectMostRecentItem()
            assertEquals(
                PatternsListUiState.Empty(PatternsListUiState.EmptyReason.NO_ENTRIES, 9),
                terminal,
            )
        }
    }

    @Test
    fun `empty state becomes NO_PATTERNS at the ten-entry threshold with no patterns`() = runTest(testDispatcher) {
        seedEntries(10)
        val vm = newViewModel()
        vm.state.test {
            val terminal = expectMostRecentItem()
            assertEquals(
                PatternsListUiState.Empty(PatternsListUiState.EmptyReason.NO_PATTERNS, 10),
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
                PatternsListUiState.Empty(PatternsListUiState.EmptyReason.NO_ENTRIES, 0),
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
                setOf(PatternAction.DROP, PatternAction.SKIP),
                loaded.cards.first().availableActions,
            )
            // ACTIVE cards never carry a wake-up back label.
            assertTrue(loaded.cards.all { it.backLabel == null })
        }
    }

    @Test
    fun `dropped and skipped patterns surface in their own sections`() = runTest(testDispatcher) {
        val entries = seedEntries(3)
        seedActivePattern("dropped-one", lastSeenMs = 100L, supporting = listOf(entries[0]))
        seedActivePattern("skipped-one", lastSeenMs = 200L, supporting = listOf(entries[1]))
        seedActivePattern("active-one", lastSeenMs = 300L, supporting = listOf(entries[2]))
        patternRepo.drop("dropped-one")
        patternRepo.skip("skipped-one")
        val vm = newViewModel()
        vm.state.test {
            val loaded = expectMostRecentItem() as PatternsListUiState.Loaded
            val bySection = loaded.cards.groupBy { it.section }
            assertEquals(listOf("active-one"), bySection[PatternSection.ACTIVE]!!.map { it.patternId })
            assertEquals(listOf("skipped-one"), bySection[PatternSection.SKIPPED]!!.map { it.patternId })
            assertEquals(listOf("dropped-one"), bySection[PatternSection.DROPPED]!!.map { it.patternId })
            assertEquals(
                setOf(PatternAction.RESTART),
                bySection[PatternSection.SKIPPED]!!.single().availableActions,
            )
            assertEquals(
                setOf(PatternAction.RESTART),
                bySection[PatternSection.DROPPED]!!.single().availableActions,
            )
            // SKIPPED card surfaces the wake-up "Back <date>" label; DROPPED does not.
            assertNotNull(bySection[PatternSection.SKIPPED]!!.single().backLabel)
            assertNull(bySection[PatternSection.DROPPED]!!.single().backLabel)
        }
    }

    @Test
    fun `drop transitions pattern and emits undo event`() = runTest(testDispatcher) {
        val entries = seedEntries(1)
        seedActivePattern("p1", lastSeenMs = 100L, supporting = entries)
        val vm = newViewModel()
        vm.events.test {
            vm.drop("p1")
            val event = awaitItem()
            assertEquals(PatternAction.DROP, event.action)
            assertNotNull(event.undo)
        }
        assertEquals(PatternState.DROPPED, patternStore.findByPatternId("p1")?.state)
    }

    @Test
    fun `skip transitions pattern to SNOOZED and emits undo event`() = runTest(testDispatcher) {
        val entries = seedEntries(1)
        seedActivePattern("p2", lastSeenMs = 100L, supporting = entries)
        val vm = newViewModel()
        vm.events.test {
            vm.skip("p2")
            val event = awaitItem()
            assertEquals(PatternAction.SKIP, event.action)
            assertNotNull(event.undo)
        }
        assertEquals(PatternState.SNOOZED, patternStore.findByPatternId("p2")?.state)
    }

    @Test
    fun `drop on an unknown patternId does not crash, refreshes state, and emits no event`() = runTest(testDispatcher) {
        seedEntries(1)
        val vm = newViewModel()
        vm.events.test {
            vm.drop("no-such-pattern")
            // dispatch swallows the missing-row throw; no PatternActionEvent for a no-op.
            expectNoEvents()
        }
        // loadState replayed — no rows, so the list is the NO_ENTRIES empty state.
        vm.state.test {
            assertEquals(
                PatternsListUiState.Empty(PatternsListUiState.EmptyReason.NO_ENTRIES, 1),
                expectMostRecentItem(),
            )
        }
    }

    @Test
    fun `skip on an unknown patternId does not crash, refreshes state, and emits no event`() = runTest(testDispatcher) {
        seedEntries(1)
        val vm = newViewModel()
        vm.events.test {
            vm.skip("no-such-pattern")
            expectNoEvents()
        }
        vm.state.test {
            assertEquals(
                PatternsListUiState.Empty(PatternsListUiState.EmptyReason.NO_ENTRIES, 1),
                expectMostRecentItem(),
            )
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
        val vm = newViewModel()
        vm.events.test {
            vm.drop("p-already-dropped")
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
            val vm = newViewModel()
            vm.events.test {
                vm.skip("p-snoozed")
                expectNoEvents()
            }
            assertEquals(PatternState.SNOOZED, patternStore.findByPatternId("p-snoozed")?.state)
        }

    @Test
    fun `undo on a stale state logs and refreshes without crashing`() = runTest(testDispatcher) {
        val entries = seedEntries(1)
        seedActivePattern("p-stale-list", lastSeenMs = 100L, supporting = entries)
        val vm = newViewModel()
        vm.skip("p-stale-list")
        assertEquals(PatternState.SNOOZED, patternStore.findByPatternId("p-stale-list")?.state)
        vm.drop("p-stale-list")
        assertEquals(PatternState.DROPPED, patternStore.findByPatternId("p-stale-list")?.state)
        // Snackbar callback fires SKIP-undo against a now-DROPPED row — PatternRepo throws.
        vm.undo(PatternUndo("p-stale-list", PatternAction.SKIP))
        assertEquals(PatternState.DROPPED, patternStore.findByPatternId("p-stale-list")?.state)
        vm.state.test {
            val loaded = expectMostRecentItem() as PatternsListUiState.Loaded
            assertTrue(loaded.cards.any { it.patternId == "p-stale-list" && it.section == PatternSection.DROPPED })
        }
    }

    @Test
    fun `undo restores a dropped pattern back to ACTIVE`() = runTest(testDispatcher) {
        val entries = seedEntries(1)
        seedActivePattern("p3", lastSeenMs = 100L, supporting = entries)
        val vm = newViewModel()
        vm.drop("p3")
        assertEquals(PatternState.DROPPED, patternStore.findByPatternId("p3")?.state)
        vm.undo(PatternUndo("p3", PatternAction.DROP))
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
        patternRepo.skip("p-restart-snooze-list")
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
