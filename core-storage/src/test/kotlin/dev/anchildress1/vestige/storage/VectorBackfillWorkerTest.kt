package dev.anchildress1.vestige.storage

import androidx.test.core.app.ApplicationProvider
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class VectorBackfillWorkerTest {

    private lateinit var boxStore: BoxStore
    private lateinit var dataDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        dataDir = File(context.filesDir, "objectbox-backfill-${System.nanoTime()}")
        boxStore = VestigeBoxStore.openAt(dataDir)
    }

    @After
    fun tearDown() {
        boxStore.close()
        BoxStore.deleteAllFiles(dataDir)
    }

    @Test
    fun `empty database returns empty stats without invoking embedder`() = runTest {
        var calls = 0
        val embedder: suspend (String) -> FloatArray = {
            calls++
            FloatArray(DIMS)
        }
        val worker = VectorBackfillWorker(boxStore, embedder)

        val stats = worker.backfill()

        assertEquals(0, stats.total)
        assertEquals(0, stats.processed)
        assertEquals(0, calls)
    }

    @Test
    fun `null-vector entries get embedded and persisted`() = runTest {
        val first = insertEntry("standup crashed", vector = null)
        val second = insertEntry("groceries", vector = null)
        val callTexts = mutableListOf<String>()
        val embedder: suspend (String) -> FloatArray = { text ->
            callTexts.add(text)
            FloatArray(DIMS) { i -> if (text == "standup crashed") i.toFloat() else 0f }
        }
        val worker = VectorBackfillWorker(boxStore, embedder)

        val stats = worker.backfill()

        assertEquals(2, stats.total)
        assertEquals(2, stats.processed)
        assertEquals(0, stats.failed)
        val entryBox = boxStore.boxFor<EntryEntity>()
        assertNotNull(entryBox[first].vector)
        assertNotNull(entryBox[second].vector)
        assertEquals(setOf("standup crashed", "groceries"), callTexts.toSet())
    }

    @Test
    fun `entries with existing vectors are skipped`() = runTest {
        val withVector = insertEntry("already done", vector = FloatArray(DIMS) { 1f })
        val pending = insertEntry("needs backfill", vector = null)
        val callTexts = mutableListOf<String>()
        val embedder: suspend (String) -> FloatArray = { text ->
            callTexts.add(text)
            FloatArray(DIMS)
        }
        val worker = VectorBackfillWorker(boxStore, embedder)

        worker.backfill()

        val entryBox = boxStore.boxFor<EntryEntity>()
        assertEquals(1f, entryBox[withVector].vector!![0])
        assertNotNull(entryBox[pending].vector)
        assertEquals(listOf("needs backfill"), callTexts)
    }

    @Test
    fun `embedder failure on one entry is logged and does not abort the pass`() = runTest {
        val good = insertEntry("succeeds", vector = null)
        val bad = insertEntry("throws", vector = null)
        val embedder: suspend (String) -> FloatArray = { text ->
            if (text == "throws") error("simulated embedder failure")
            FloatArray(DIMS) { 1f }
        }
        val worker = VectorBackfillWorker(boxStore, embedder)

        val stats = worker.backfill()

        assertEquals(2, stats.total)
        assertEquals(1, stats.processed)
        assertEquals(1, stats.failed)
        val entryBox = boxStore.boxFor<EntryEntity>()
        assertNotNull(entryBox[good].vector)
        assertNull(entryBox[bad].vector)
    }

    @Test
    fun `cancellation mid-pass stops cleanly without losing prior progress`() = runBlocking {
        repeat(5) { insertEntry("entry $it", vector = null) }
        val processedCount = AtomicInteger(0)
        val embedder: suspend (String) -> FloatArray = {
            // Yield first so the cancel below can land between embeddings.
            delay(20)
            processedCount.incrementAndGet()
            FloatArray(DIMS)
        }
        val worker = VectorBackfillWorker(boxStore, embedder)
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val job = parent.async { worker.backfill() }
        delay(50) // let the worker process a couple of entries
        parent.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        assertThrows(CancellationException::class.java) {
            runBlocking { job.await() }
        }

        val entryBox = boxStore.boxFor<EntryEntity>()
        val embedded = entryBox.all.count { it.vector != null }
        assertEquals(
            "Embedded entries must match processed count — partial progress is durable.",
            processedCount.get(),
            embedded,
        )
        assertTrue("Cancellation must arrive before all 5 entries finish", embedded < 5)
    }

    private fun insertEntry(text: String, vector: FloatArray?): Long {
        val entryBox = boxStore.boxFor<EntryEntity>()
        return entryBox.put(
            EntryEntity(
                entryText = text,
                timestampEpochMs = System.currentTimeMillis(),
                markdownFilename = "test-${System.nanoTime()}.md",
                vector = vector,
            ),
        )
    }

    private companion object {
        const val DIMS = 768
    }
}
