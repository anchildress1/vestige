package dev.anchildress1.vestige.ui.history

import app.cash.turbine.test
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.storage.MarkdownEntryStore
import dev.anchildress1.vestige.testing.cleanupObjectBoxTempRoot
import dev.anchildress1.vestige.testing.newInMemoryObjectBoxDirectory
import dev.anchildress1.vestige.testing.newModuleTempRoot
import dev.anchildress1.vestige.testing.openInMemoryBoxStore
import io.objectbox.BoxStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = HistoryTestApplication::class)
class HistoryViewModelTest {

    private lateinit var tempRoot: File
    private lateinit var dataDir: File
    private lateinit var boxStore: BoxStore
    private lateinit var entryStore: EntryStore
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        tempRoot = newModuleTempRoot("vestige-history-viewmodel-")
        dataDir = newInMemoryObjectBoxDirectory("ob-history-vm-")
        boxStore = openInMemoryBoxStore(dataDir)
        entryStore = EntryStore(
            boxStore,
            MarkdownEntryStore(File(tempRoot, "md-${System.nanoTime()}").apply { mkdirs() }),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        boxStore.close()
        cleanupObjectBoxTempRoot(tempRoot, dataDir)
    }

    // pos — seeded entries emit rows, newest first

    @Test
    fun `pos — seeded completed entries emit rows in reverse-chronological order`() = runTest(testDispatcher) {
        seedEntry("oldest", timestampEpochMs = 1_000_000L, ExtractionStatus.COMPLETED)
        seedEntry("middle", timestampEpochMs = 2_000_000L, ExtractionStatus.COMPLETED)
        seedEntry("newest", timestampEpochMs = 3_000_000L, ExtractionStatus.COMPLETED)

        val vm = newViewModel()
        vm.state.test {
            val terminal = expectMostRecentItem()
            assertFalse(terminal.loading)
            assertEquals(3, terminal.entries.size)
            // newest first
            assertEquals("newest", terminal.entries[0].snippet)
            assertEquals("oldest", terminal.entries[2].snippet)
        }
    }

    // neg — empty store emits empty list

    @Test
    fun `neg — empty store emits empty non-loading state`() = runTest(testDispatcher) {
        val vm = newViewModel()
        vm.state.test {
            val terminal = expectMostRecentItem()
            assertFalse(terminal.loading)
            assertEquals(0, terminal.entries.size)
        }
    }

    // neg — pending entries are excluded

    @Test
    fun `neg — pending entries are excluded from history`() = runTest(testDispatcher) {
        seedEntry("pending-entry", timestampEpochMs = 1_000_000L, ExtractionStatus.PENDING)
        seedEntry("completed-entry", timestampEpochMs = 2_000_000L, ExtractionStatus.COMPLETED)

        val vm = newViewModel()
        vm.state.test {
            val terminal = expectMostRecentItem()
            assertEquals(1, terminal.entries.size)
            assertEquals("completed-entry", terminal.entries[0].snippet)
        }
    }

    // edge — limit enforced

    @Test
    fun `edge — more than 100 completed entries returns exactly 100 rows`() = runTest(testDispatcher) {
        repeat(110) { i ->
            seedEntry("entry-$i", timestampEpochMs = i.toLong() * 1_000L, ExtractionStatus.COMPLETED)
        }

        val vm = newViewModel()
        vm.state.test {
            val terminal = expectMostRecentItem()
            assertEquals(100, terminal.entries.size)
        }
    }

    // edge — durationMs is mapped correctly

    @Test
    fun `edge — durationMs is preserved in the emitted summary`() = runTest(testDispatcher) {
        seedEntry("voice", timestampEpochMs = 1_000_000L, ExtractionStatus.COMPLETED, durationMs = 242_000L)

        val vm = newViewModel()
        vm.state.test {
            val terminal = expectMostRecentItem()
            assertEquals(242_000L, terminal.entries.single().durationMs)
        }
    }

    // pos — dataRevision triggers reload when a new completed entry is added after initial load
    @Test
    fun `pos — entry added after initial load appears when dataRevision increments`() = runTest(testDispatcher) {
        val dataRevision = MutableStateFlow(0L)
        val vm = HistoryViewModel(entryStore, ioDispatcher = testDispatcher, dataRevision = dataRevision)

        vm.state.test {
            val initial = expectMostRecentItem()
            assertFalse(initial.loading)
            assertEquals(0, initial.entries.size)

            seedEntry("late-entry", timestampEpochMs = 1_000_000L, ExtractionStatus.COMPLETED)
            dataRevision.value += 1

            val updated = awaitItem()
            assertFalse(updated.loading)
            assertEquals(1, updated.entries.size)
            assertEquals("late-entry", updated.entries[0].snippet)
        }
    }

    // pos — pending entry transitions to visible when extraction completes and dataRevision increments
    // This is the bug scenario: user opens History immediately after recording while extraction is
    // still in-flight. dataRevision increment on terminal extraction status triggers the reload.

    @Test
    fun `pos — pending entry becomes visible when extraction completes and dataRevision increments`() =
        runTest(testDispatcher) {
            val dataRevision = MutableStateFlow(0L)
            val pendingEntity = seedEntry("just-recorded", timestampEpochMs = 1_000_000L, ExtractionStatus.PENDING)
            val vm = HistoryViewModel(entryStore, ioDispatcher = testDispatcher, dataRevision = dataRevision)

            vm.state.test {
                val initial = expectMostRecentItem()
                assertFalse(initial.loading)
                assertEquals(0, initial.entries.size)

                entryStore.completeEntry(pendingEntity.id, emptyResolved, templateLabel = null)
                dataRevision.value += 1

                val updated = awaitItem()
                assertEquals(1, updated.entries.size)
                assertEquals("just-recorded", updated.entries[0].snippet)
            }
        }

    // err — store failure on load produces empty state without crash

    @Test
    fun `err — store failure on load produces empty non-loading state`() = runTest(testDispatcher) {
        val errDataDir = newInMemoryObjectBoxDirectory("ob-history-vm-err-")
        val errBoxStore = openInMemoryBoxStore(errDataDir)
        val errEntryStore = EntryStore(
            errBoxStore,
            MarkdownEntryStore(File(tempRoot, "md-err-${System.nanoTime()}").apply { mkdirs() }),
        )
        errBoxStore.close()

        val vm = HistoryViewModel(errEntryStore, zoneId = java.time.ZoneOffset.UTC, ioDispatcher = testDispatcher)
        vm.state.test {
            val terminal = expectMostRecentItem()
            assertFalse(terminal.loading)
            assertEquals(0, terminal.entries.size)
        }
    }

    private fun newViewModel(): HistoryViewModel = HistoryViewModel(entryStore, ioDispatcher = testDispatcher)
    // Note: zoneId defaults to ZoneOffset.UTC in HistoryViewModel — not passed here to keep tests terse.

    private fun seedEntry(
        text: String,
        timestampEpochMs: Long,
        status: ExtractionStatus,
        durationMs: Long = 0L,
    ): EntryEntity = entryStore.createPendingEntry(text, Instant.ofEpochMilli(timestampEpochMs), durationMs)
        .let { entryStore.readEntry(it)!! }
        .also { entity ->
            if (status == ExtractionStatus.COMPLETED) {
                entryStore.completeEntry(entity.id, emptyResolved, templateLabel = null)
            }
        }

    private companion object {
        private val emptyResolved = dev.anchildress1.vestige.model.ResolvedExtraction(emptyMap())
    }
}
