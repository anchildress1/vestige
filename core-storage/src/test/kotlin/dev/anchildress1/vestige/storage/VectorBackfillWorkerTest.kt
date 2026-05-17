package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.testing.newInMemoryObjectBoxDirectory
import dev.anchildress1.vestige.testing.openInMemoryBoxStore
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import io.objectbox.query.QueryBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        dataDir = newInMemoryObjectBoxDirectory("objectbox-backfill-")
        boxStore = openInMemoryBoxStore(dataDir)
    }

    @After
    fun tearDown() {
        boxStore.close()
        BoxStore.deleteAllFiles(dataDir)
    }

    @Test
    fun `pending work distinguishes embeddings from legacy cleanup`() {
        val worker = VectorBackfillWorker(boxStore) { error("embedder must not be called") }

        assertFalse(worker.hasPendingWork())
        assertFalse(worker.hasPendingEmbeddings())

        insertEntry(status = ExtractionStatus.PENDING, vectorSchemaVersion = 0)
        assertFalse("PENDING rows without a vector are not work yet", worker.hasPendingWork())
        assertFalse(worker.hasPendingEmbeddings())

        insertEntry(status = ExtractionStatus.FAILED, vector = FloatArray(DIMS), vectorSchemaVersion = 0)
        assertTrue("legacy non-COMPLETED vectors must be cleared", worker.hasPendingWork())
        assertFalse("cleanup-only work does not require embedding artifacts", worker.hasPendingEmbeddings())

        insertEntry(status = ExtractionStatus.COMPLETED, vectorSchemaVersion = CURRENT)
        assertTrue("already-current rows do not hide the cleanup row", worker.hasPendingWork())

        insertEntry(status = ExtractionStatus.COMPLETED, vectorSchemaVersion = 0)
        assertTrue("COMPLETED + stale schema is pending work", worker.hasPendingWork())
        assertTrue("COMPLETED + stale schema requires embeddings", worker.hasPendingEmbeddings())
    }

    @Test
    fun `empty database returns empty stats without invoking embedder`() = runTest {
        var calls = 0
        val worker = VectorBackfillWorker(boxStore) {
            calls++
            FloatArray(DIMS)
        }

        val stats = worker.backfill()

        assertEquals(0, stats.total)
        assertEquals(0, stats.processed)
        assertEquals(0, calls)
    }

    @Test
    fun `embeds the synthesized distillation, never the raw entryText`() = runTest {
        insertEntry(
            tagNames = listOf("standup", "flattened"),
            observations = listOf("meeting ran long", "lead was absent"),
            commitmentTopic = "alice",
            entryText = "uh so like the whole thing just kind of crashed and burned today",
        )
        val callTexts = mutableListOf<String>()
        val worker = VectorBackfillWorker(boxStore) { text ->
            callTexts.add(text)
            FloatArray(DIMS) { 1f }
        }

        val stats = worker.backfill()

        assertEquals(1, stats.processed)
        assertEquals(0, stats.failed)
        assertEquals(
            listOf("standup flattened. meeting ran long. lead was absent. alice"),
            callTexts,
        )
        val row = boxStore.boxFor<EntryEntity>().all.single()
        assertNotNull(row.vector)
        assertEquals(CURRENT, row.vectorSchemaVersion)
    }

    @Test
    fun `non-COMPLETED rows without legacy vectors remain untouched and are not counted`() = runTest {
        val nonTerminal = listOf(
            ExtractionStatus.PENDING,
            ExtractionStatus.RUNNING,
            ExtractionStatus.FAILED,
            ExtractionStatus.TIMED_OUT,
        )
        nonTerminal.forEach { insertEntry(status = it, vectorSchemaVersion = 0) }
        val worker = VectorBackfillWorker(boxStore) { error("embedder must not be called for non-COMPLETED rows") }

        val stats = worker.backfill()

        assertEquals(0, stats.total)
        assertEquals(0, stats.processed)
        assertTrue(boxStore.boxFor<EntryEntity>().all.all { it.vector == null && it.vectorSchemaVersion == 0 })
    }

    @Test
    fun `legacy non-COMPLETED vectors are cleared and schema-stamped without embedding`() = runTest {
        val ids = listOf(
            insertEntry(status = ExtractionStatus.PENDING, vector = FloatArray(DIMS) { 1f }, vectorSchemaVersion = 0),
            insertEntry(status = ExtractionStatus.FAILED, vector = FloatArray(DIMS) { 1f }, vectorSchemaVersion = 0),
        )
        val worker = VectorBackfillWorker(boxStore) { error("embedder must not be called for cleanup-only rows") }

        val stats = worker.backfill(batchSize = 1)

        assertEquals(2, stats.total)
        assertEquals(0, stats.processed)
        assertEquals(2, stats.skipped)
        val entryBox = boxStore.boxFor<EntryEntity>()
        ids.forEach { id ->
            assertNull(entryBox[id].vector)
            assertEquals(CURRENT, entryBox[id].vectorSchemaVersion)
        }
    }

    @Test
    fun `rows already at the current schema are skipped on re-run`() = runTest {
        val current = insertEntry(vectorSchemaVersion = CURRENT, vector = null)
        val stale = insertEntry(vectorSchemaVersion = 0)
        val callTexts = mutableListOf<String>()
        val worker = VectorBackfillWorker(boxStore) { text ->
            callTexts.add(text)
            FloatArray(DIMS)
        }

        val stats = worker.backfill()

        assertEquals(1, stats.total)
        assertEquals(1, stats.processed)
        assertEquals(1, callTexts.size)
        val entryBox = boxStore.boxFor<EntryEntity>()
        assertNull("current-schema row left untouched", entryBox[current].vector)
        assertNotNull(entryBox[stale].vector)
    }

    @Test
    fun `stale previously-embedded rows are re-embedded against the new source`() = runTest {
        val staleVector = FloatArray(DIMS) { 0.5f }
        val id = insertEntry(
            tagNames = listOf("rebackfill"),
            vector = staleVector,
            vectorSchemaVersion = 0,
        )
        val worker = VectorBackfillWorker(boxStore) { text ->
            assertEquals("rebackfill", text)
            FloatArray(DIMS) { 9f }
        }

        val stats = worker.backfill()

        assertEquals(1, stats.processed)
        val row = boxStore.boxFor<EntryEntity>()[id]
        assertEquals(9f, row.vector!![0])
        assertEquals(CURRENT, row.vectorSchemaVersion)
    }

    @Test
    fun `COMPLETED row with nothing distillable clears any legacy vector, stamps schema, and terminates the sweep`() =
        runTest {
            val id = insertEntry(
                tagNames = emptyList(),
                observations = emptyList(),
                commitmentTopic = null,
                vector = FloatArray(DIMS) { 0.5f },
            )
            val worker = VectorBackfillWorker(boxStore) { error("embedder must not be called for blank distillation") }

            val stats = worker.backfill()

            assertEquals(1, stats.total)
            assertEquals(0, stats.processed)
            assertEquals(0, stats.failed)
            assertEquals(1, stats.skipped)
            val row = boxStore.boxFor<EntryEntity>()[id]
            assertNull("no vector — null is a zero cosine contribution", row.vector)
            assertEquals(CURRENT, row.vectorSchemaVersion)
            assertFalse("stamped row must not be re-selected", worker.hasPendingWork())
        }

    @Test
    fun `embedder failure leaves the row stale so the next pass retries it`() = runTest {
        val good = insertEntry(tagNames = listOf("succeeds"))
        val bad = insertEntry(tagNames = listOf("throws"))
        val worker = VectorBackfillWorker(boxStore) { text ->
            if (text == "throws") error("simulated embedder failure")
            FloatArray(DIMS) { 1f }
        }

        val stats = worker.backfill()

        assertEquals(2, stats.total)
        assertEquals(1, stats.processed)
        assertEquals(1, stats.failed)
        val entryBox = boxStore.boxFor<EntryEntity>()
        assertNotNull(entryBox[good].vector)
        assertEquals(CURRENT, entryBox[good].vectorSchemaVersion)
        assertNull(entryBox[bad].vector)
        assertEquals("failed row stays stale for retry", 0, entryBox[bad].vectorSchemaVersion)
    }

    @Test
    fun `wrong-dimension embedder output is a failure and is not persisted`() = runTest {
        val target = insertEntry(tagNames = listOf("dimension drift"))
        val worker = VectorBackfillWorker(boxStore) { FloatArray(64) { 1f } } // expected 768

        val stats = worker.backfill()

        assertEquals(1, stats.total)
        assertEquals(0, stats.processed)
        assertEquals(1, stats.failed)
        val row = boxStore.boxFor<EntryEntity>()[target]
        assertNull(row.vector)
        assertEquals(0, row.vectorSchemaVersion)
    }

    @Test
    fun `all entries are processed across multiple paged batches without skipping rows that mutate out of the query`() =
        runTest {
            val cleanupId =
                insertEntry(status = ExtractionStatus.FAILED, vector = FloatArray(DIMS) { 2f }, vectorSchemaVersion = 0)
            val ids = listOf(cleanupId) + (0 until 5).map { insertEntry(tagNames = listOf("entry-$it")) }
            val seen = mutableListOf<String>()
            val worker = VectorBackfillWorker(boxStore) { text ->
                seen.add(text)
                FloatArray(DIMS)
            }

            val stats = worker.backfill(batchSize = 2)

            assertEquals(6, stats.total)
            assertEquals(5, stats.processed)
            assertEquals(1, stats.skipped)
            assertEquals((0 until 5).map { "entry-$it" }.toSet(), seen.toSet())
            val entryBox = boxStore.boxFor<EntryEntity>()
            assertNull(entryBox[cleanupId].vector)
            assertEquals(CURRENT, entryBox[cleanupId].vectorSchemaVersion)
            assertTrue(ids.drop(1).all { entryBox[it].vector != null && entryBox[it].vectorSchemaVersion == CURRENT })
        }

    @Test
    fun `non-positive backfill batch size is rejected`() {
        val worker = VectorBackfillWorker(boxStore) { FloatArray(DIMS) }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { worker.backfill(batchSize = 0) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { worker.backfill(batchSize = -1) }
        }
    }

    @Test
    fun `cancellation mid-pass stops cleanly without losing prior progress`() = runBlocking {
        val ids = (0 until 5).map { insertEntry(tagNames = listOf("entry-$it")) }
        val processedCount = AtomicInteger(0)
        val blocked = CompletableDeferred<Unit>()
        // Gate: let exactly 2 embeddings complete, then block the 3rd indefinitely so the
        // cancel arrives at a deterministic point rather than racing a timer.
        val worker = VectorBackfillWorker(boxStore) {
            if (processedCount.get() >= 2) {
                blocked.complete(Unit)
                delay(Long.MAX_VALUE)
            }
            processedCount.incrementAndGet()
            FloatArray(DIMS)
        }
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val job = parent.async { worker.backfill() }
        blocked.await() // wait until the worker is parked inside the 3rd embedding
        job.cancel()
        assertThrows(CancellationException::class.java) {
            runBlocking { job.await() }
        }

        val entryBox = boxStore.boxFor<EntryEntity>()
        val embedded = ids.count { entryBox[it].vector != null }
        assertEquals(
            "Exactly 2 entries must be durable after cancel",
            processedCount.get(),
            embedded,
        )
        assertEquals("Exactly 2 entries processed before gate", 2, embedded)
        assertTrue(
            "Every embedded row is stamped to the current schema",
            ids.all { entryBox[it].vector == null || entryBox[it].vectorSchemaVersion == CURRENT },
        )
    }

    @Suppress("LongParameterList") // Test seam mirrors the fields buildEmbeddingText reads.
    private fun insertEntry(
        tagNames: List<String> = listOf("default-tag"),
        observations: List<String> = emptyList(),
        commitmentTopic: String? = null,
        entryText: String = "raw verbatim transcription body",
        status: ExtractionStatus = ExtractionStatus.COMPLETED,
        vector: FloatArray? = null,
        vectorSchemaVersion: Int = 0,
    ): Long {
        val entryBox = boxStore.boxFor<EntryEntity>()
        val tagBox = boxStore.boxFor<TagEntity>()
        // Reuse an existing tag row by name — `TagEntity.name` is @Unique, so two entries that
        // share a tag must link the same row (mirrors EntryStore.attachTags).
        val tagEntities = tagNames.map { name ->
            tagBox.query().equal(TagEntity_.name, name, QueryBuilder.StringOrder.CASE_SENSITIVE)
                .build()
                .use { it.findFirst() }
                ?: TagEntity(name = name, entryCount = 1).also { tagBox.put(it) }
        }
        val observationsJson = JSONArray().apply {
            observations.forEach {
                put(JSONObject().put("text", it).put("evidence", "theme-noticing").put("fields", JSONArray()))
            }
        }.toString()
        val commitmentJson = commitmentTopic?.let {
            JSONObject().put("text", "committed").put("topic_or_person", it).toString()
        }
        val entry = EntryEntity(
            entryText = entryText,
            timestampEpochMs = System.currentTimeMillis(),
            markdownFilename = "test-${System.nanoTime()}.md",
            statedCommitmentJson = commitmentJson,
            entryObservationsJson = observationsJson,
            extractionStatus = status,
            vector = vector,
            vectorSchemaVersion = vectorSchemaVersion,
        )
        val id = entryBox.put(entry)
        if (tagEntities.isNotEmpty()) {
            entry.tags.addAll(tagEntities)
            entryBox.put(entry)
        }
        return id
    }

    private companion object {
        const val DIMS = 768
        const val CURRENT = EntryEntity.CURRENT_VECTOR_SCHEMA_VERSION
    }
}
