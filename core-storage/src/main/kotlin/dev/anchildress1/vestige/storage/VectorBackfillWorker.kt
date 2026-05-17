package dev.anchildress1.vestige.storage

import android.util.Log
import dev.anchildress1.vestige.model.ExtractionStatus
import io.objectbox.Box
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import io.objectbox.query.Query
import io.objectbox.query.QueryBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/**
 * One-shot, idempotent vector backfill. Embeds and persists any COMPLETED [EntryEntity] whose
 * [EntryEntity.vectorSchemaVersion] is behind [EntryEntity.CURRENT_VECTOR_SCHEMA_VERSION] —
 * which covers both never-embedded rows (default 0) and rows embedded against the old raw
 * `entryText` source before Story 3.11. Legacy non-COMPLETED rows that still carry a vector
 * are cleaned in the same sweep: their old raw-transcript signal is cleared immediately, but
 * their stale schema is preserved so a later COMPLETED transition still gets re-embedded.
 *
 * The embedding target is [buildEmbeddingText] (tags + observation texts + commitment topic),
 * not the verbatim transcription. Cooperative with cancellation — if the parent scope is
 * cancelled mid-pass, committed progress survives and the remaining stale rows get picked up
 * on the next invocation.
 */
@Suppress("TooManyFunctions") // Query builders + paged batch helpers are the worker's whole job.
class VectorBackfillWorker(private val boxStore: BoxStore, private val embedder: suspend (String) -> FloatArray) {

    /** Cheap presence check for any stale vector work: embedding or legacy-vector cleanup. */
    fun hasPendingWork(): Boolean = pendingEmbeddingCount() > 0 || pendingLegacyCleanupCount() > 0

    /**
     * Cheap presence check: true iff at least one COMPLETED entry still needs embedding work.
     * Callers use this to gate expensive setup (artifact SHA-256 verification, embedder
     * construction) so cleanup-only sweeps can still run while artifacts are absent.
     */
    fun hasPendingEmbeddings(): Boolean = pendingEmbeddingCount() > 0

    suspend fun backfill(batchSize: Int = DEFAULT_BATCH_SIZE): BackfillStats = coroutineScope {
        require(batchSize > 0) { "VectorBackfillWorker.backfill batchSize must be > 0 (got $batchSize)" }
        val entryBox = boxStore.boxFor<EntryEntity>()
        val pendingEmbeddings = pendingEmbeddingCount()
        val pendingLegacyCleanup = pendingLegacyCleanupCount()
        val total = pendingEmbeddings + pendingLegacyCleanup
        if (total == 0L) {
            return@coroutineScope BackfillStats.empty()
        }
        Log.i(
            TAG,
            "Vector backfill: $pendingEmbeddings embeddings + $pendingLegacyCleanup legacy cleanups pending",
        )
        val started = System.currentTimeMillis()
        var progress = BackfillProgress.empty()
        progress += processPaged(
            batchSize = batchSize,
            loadBatch = ::loadLegacyCleanupBatch,
            processBatch = { batch -> processLegacyCleanupBatch(entryBox, batch) },
        )
        progress += processPaged(
            batchSize = batchSize,
            loadBatch = ::loadEmbeddingBatch,
            processBatch = { batch -> processEmbeddingBatch(entryBox, batch) },
        )
        val stats = BackfillStats(
            total = total.toInt(),
            processed = progress.processed,
            failed = progress.failed,
            durationMs = System.currentTimeMillis() - started,
            skipped = progress.skipped,
        )
        Log.i(TAG, "Vector backfill complete: $stats")
        stats
    }

    private suspend fun processPaged(
        batchSize: Int,
        loadBatch: (Long, Long) -> List<EntryEntity>,
        processBatch: suspend (List<EntryEntity>) -> BackfillProgress,
    ): BackfillProgress {
        var progress = BackfillProgress.empty()
        var lastSeenId = 0L
        while (true) {
            val batch = loadBatch(lastSeenId, batchSize.toLong())
            if (batch.isEmpty()) return progress
            progress += processBatch(batch)
            lastSeenId = batch.last().id
            // Cooperative yield between batches so a large backlog can't monopolize the shared
            // container dispatcher and starve foreground inference.
            yield()
        }
    }

    private suspend fun processLegacyCleanupBatch(
        entryBox: Box<EntryEntity>,
        batch: List<EntryEntity>,
    ): BackfillProgress {
        batch.forEach { entry ->
            currentCoroutineContext().ensureActive()
            clearVector(entryBox, entry)
        }
        return BackfillProgress(processed = 0, failed = 0, skipped = batch.size)
    }

    private suspend fun processEmbeddingBatch(entryBox: Box<EntryEntity>, batch: List<EntryEntity>): BackfillProgress {
        var progress = BackfillProgress.empty()
        for (entry in batch) {
            currentCoroutineContext().ensureActive()
            progress += processEmbeddingEntry(entryBox, entry)
        }
        return progress
    }

    private suspend fun processEmbeddingEntry(entryBox: Box<EntryEntity>, entry: EntryEntity): BackfillProgress = try {
        val text = buildEmbeddingText(entry)
        if (text.isBlank()) {
            Log.d(TAG, "Skipped embedding for entry ${entry.id} — no embeddable text after distillation")
            clearVectorAndMarkCurrent(entryBox, entry)
            BackfillProgress(processed = 0, failed = 0, skipped = 1)
        } else {
            embedAndPersist(entryBox, entry, text)
            BackfillProgress(processed = 1, failed = 0, skipped = 0)
        }
    } catch (cancel: CancellationException) {
        // Coroutine cancellation must not look like an embedder failure — rethrow so the outer
        // scope unwinds cleanly and partial progress already committed stays intact.
        throw cancel
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        Log.w(TAG, "Vector backfill failed for entry ${entry.id}", error)
        BackfillProgress(processed = 0, failed = 1, skipped = 0)
    }

    private fun clearVector(entryBox: Box<EntryEntity>, entry: EntryEntity) {
        // Non-COMPLETED rows may still resolve later, so clear the stale cosine signal now but
        // leave the stale schema in place. When extraction finally lands, the COMPLETED row will
        // still qualify for the distilled re-embed pass.
        entry.vector = null
        entryBox.put(entry)
    }

    private fun clearVectorAndMarkCurrent(entryBox: Box<EntryEntity>, entry: EntryEntity) {
        // A COMPLETED row with no valid distilled target must contribute zero cosine signal and
        // stop re-queuing forever. Clear the vector, then mark the row current.
        clearVector(entryBox, entry)
        entry.vectorSchemaVersion = EntryEntity.CURRENT_VECTOR_SCHEMA_VERSION
        entryBox.put(entry)
    }

    private suspend fun embedAndPersist(entryBox: Box<EntryEntity>, entry: EntryEntity, text: String) {
        val vector = embedder(text)
        check(vector.size.toLong() == EntryEntity.EMBEDDING_DIMENSIONS) {
            "Embedder returned ${vector.size}-d vector; expected ${EntryEntity.EMBEDDING_DIMENSIONS}"
        }
        entry.vector = vector
        entry.vectorSchemaVersion = EntryEntity.CURRENT_VECTOR_SCHEMA_VERSION
        entryBox.put(entry)
    }

    private fun pendingEmbeddingCount(): Long = pendingEmbeddingQuery().use { it.count() }

    private fun pendingLegacyCleanupCount(): Long = pendingLegacyCleanupQuery().use { it.count() }

    private fun loadEmbeddingBatch(afterIdExclusive: Long, limit: Long): List<EntryEntity> = pendingEmbeddingQuery(
        afterIdExclusive = afterIdExclusive,
    ).use { it.find(0, limit) }

    private fun loadLegacyCleanupBatch(afterIdExclusive: Long, limit: Long): List<EntryEntity> =
        pendingLegacyCleanupQuery(
            afterIdExclusive = afterIdExclusive,
        ).use { it.find(0, limit) }

    private fun pendingEmbeddingQuery(afterIdExclusive: Long = 0L): Query<EntryEntity> =
        boxStore.boxFor<EntryEntity>().query()
            .equal(
                EntryEntity_.extractionStatus,
                ExtractionStatus.COMPLETED.name,
                QueryBuilder.StringOrder.CASE_SENSITIVE,
            )
            .less(
                EntryEntity_.vectorSchemaVersion,
                EntryEntity.CURRENT_VECTOR_SCHEMA_VERSION.toLong(),
            )
            .greater(EntryEntity_.id, afterIdExclusive)
            .order(EntryEntity_.id)
            .build()

    private fun pendingLegacyCleanupQuery(afterIdExclusive: Long = 0L): Query<EntryEntity> =
        boxStore.boxFor<EntryEntity>().query()
            .notEqual(
                EntryEntity_.extractionStatus,
                ExtractionStatus.COMPLETED.name,
                QueryBuilder.StringOrder.CASE_SENSITIVE,
            )
            .less(
                EntryEntity_.vectorSchemaVersion,
                EntryEntity.CURRENT_VECTOR_SCHEMA_VERSION.toLong(),
            )
            .notNull(EntryEntity_.vector)
            .greater(EntryEntity_.id, afterIdExclusive)
            .order(EntryEntity_.id)
            .build()

    data class BackfillStats(
        val total: Int,
        val processed: Int,
        val failed: Int,
        val durationMs: Long,
        val skipped: Int = 0,
    ) {
        companion object {
            fun empty(): BackfillStats = BackfillStats(total = 0, processed = 0, failed = 0, durationMs = 0)
        }
    }

    private data class BackfillProgress(val processed: Int, val failed: Int, val skipped: Int) {
        operator fun plus(other: BackfillProgress): BackfillProgress = BackfillProgress(
            processed = processed + other.processed,
            failed = failed + other.failed,
            skipped = skipped + other.skipped,
        )

        companion object {
            fun empty(): BackfillProgress = BackfillProgress(processed = 0, failed = 0, skipped = 0)
        }
    }

    private companion object {
        const val TAG = "VectorBackfill"
        const val DEFAULT_BATCH_SIZE = 16
    }
}
