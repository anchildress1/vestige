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
 * `entryText` source before Story 3.11. Non-COMPLETED rows are skipped: their distilled
 * fields don't exist yet, so they're re-swept when extraction completes.
 *
 * The embedding target is [buildEmbeddingText] (tags + observation texts + commitment topic),
 * not the verbatim transcription. Cooperative with cancellation — if the parent scope is
 * cancelled mid-pass, committed progress survives and the remaining stale rows get picked up
 * on the next invocation.
 */
class VectorBackfillWorker(private val boxStore: BoxStore, private val embedder: suspend (String) -> FloatArray) {

    /**
     * Cheap presence check: true iff at least one COMPLETED entry is at a stale
     * [EntryEntity.vectorSchemaVersion]. Callers use this to gate expensive setup (artifact
     * SHA-256 verification, embedder construction) so a cold start with no pending work pays
     * no IO.
     */
    fun hasPendingWork(): Boolean = pendingQuery().use { it.count() > 0 }

    suspend fun backfill(batchSize: Int = DEFAULT_BATCH_SIZE): BackfillStats = coroutineScope {
        require(batchSize > 0) { "VectorBackfillWorker.backfill batchSize must be > 0 (got $batchSize)" }
        val entryBox = boxStore.boxFor<EntryEntity>()
        val pending = pendingEntries()
        if (pending.isEmpty()) {
            return@coroutineScope BackfillStats.empty()
        }
        Log.i(TAG, "Vector backfill: ${pending.size} entries pending")
        val started = System.currentTimeMillis()
        var progress = BackfillProgress.empty()
        for (batch in pending.chunked(batchSize)) {
            progress += processBatch(entryBox, batch)
            // Cooperative yield between batches so a large backlog can't monopolize the shared
            // container dispatcher and starve foreground inference.
            yield()
        }
        val stats = BackfillStats(
            total = pending.size,
            processed = progress.processed,
            failed = progress.failed,
            durationMs = System.currentTimeMillis() - started,
            skipped = progress.skipped,
        )
        Log.i(TAG, "Vector backfill complete: $stats")
        stats
    }

    private fun pendingEntries(): List<EntryEntity> = pendingQuery().use { it.find() }

    private suspend fun processBatch(entryBox: Box<EntryEntity>, batch: List<EntryEntity>): BackfillProgress {
        var progress = BackfillProgress.empty()
        for (entry in batch) {
            currentCoroutineContext().ensureActive()
            progress += processEntry(entryBox, entry)
        }
        return progress
    }

    private suspend fun processEntry(entryBox: Box<EntryEntity>, entry: EntryEntity): BackfillProgress = try {
        val text = buildEmbeddingText(entry)
        if (text.isBlank()) {
            Log.d(TAG, "Skipped embedding for entry ${entry.id} — no embeddable text after distillation")
            stampCurrentSchema(entryBox, entry)
            BackfillProgress(processed = 0, failed = 0, skipped = 1)
        } else {
            embedAndPersist(entryBox, entry, text)
            BackfillProgress(processed = 1, failed = 0)
        }
    } catch (cancel: CancellationException) {
        // Coroutine cancellation must not look like an embedder failure — rethrow so the outer
        // scope unwinds cleanly and partial progress already committed stays intact.
        throw cancel
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        Log.w(TAG, "Vector backfill failed for entry ${entry.id}", error)
        BackfillProgress(processed = 0, failed = 1)
    }

    private fun stampCurrentSchema(entryBox: Box<EntryEntity>, entry: EntryEntity) {
        // COMPLETED but the model distilled nothing embeddable. Stamp to the current schema so
        // the sweep terminates instead of re-selecting this row every cold start; leave `vector`
        // null (zero cosine contribution).
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

    private fun pendingQuery(): Query<EntryEntity> = boxStore.boxFor<EntryEntity>().query()
        .equal(
            EntryEntity_.extractionStatus,
            ExtractionStatus.COMPLETED.name,
            QueryBuilder.StringOrder.CASE_SENSITIVE,
        )
        .less(
            EntryEntity_.vectorSchemaVersion,
            EntryEntity.CURRENT_VECTOR_SCHEMA_VERSION.toLong(),
        )
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
