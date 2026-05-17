package dev.anchildress1.vestige.storage

import android.util.Log
import dev.anchildress1.vestige.model.ExtractionStatus
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import io.objectbox.query.Query
import io.objectbox.query.QueryBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
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
        val pending: List<EntryEntity> = pendingQuery().use { it.find() }
        if (pending.isEmpty()) {
            return@coroutineScope BackfillStats.empty()
        }
        Log.i(TAG, "Vector backfill: ${pending.size} entries pending")
        val started = System.currentTimeMillis()
        var processed = 0
        var failed = 0
        for (batch in pending.chunked(batchSize)) {
            for (entry in batch) {
                ensureActive()
                try {
                    val text = buildEmbeddingText(entry)
                    if (text.isBlank()) {
                        // COMPLETED but the model distilled nothing embeddable. Stamp to the
                        // current schema so the sweep terminates instead of re-selecting this
                        // row every cold start; leave `vector` null (zero cosine contribution).
                        entry.vectorSchemaVersion = EntryEntity.CURRENT_VECTOR_SCHEMA_VERSION
                        entryBox.put(entry)
                        continue
                    }
                    val vector = embedder(text)
                    check(vector.size.toLong() == EntryEntity.EMBEDDING_DIMENSIONS) {
                        "Embedder returned ${vector.size}-d vector; expected ${EntryEntity.EMBEDDING_DIMENSIONS}"
                    }
                    entry.vector = vector
                    entry.vectorSchemaVersion = EntryEntity.CURRENT_VECTOR_SCHEMA_VERSION
                    entryBox.put(entry)
                    processed++
                } catch (cancel: CancellationException) {
                    // Coroutine cancellation must not look like an embedder failure — rethrow so
                    // the outer scope unwinds cleanly and partial progress already committed
                    // stays intact.
                    throw cancel
                } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                    failed++
                    Log.w(TAG, "Vector backfill failed for entry ${entry.id}", error)
                }
            }
            // Cooperative yield between batches so a large backlog can't monopolize the shared
            // container dispatcher and starve foreground inference.
            yield()
        }
        val stats = BackfillStats(
            total = pending.size,
            processed = processed,
            failed = failed,
            durationMs = System.currentTimeMillis() - started,
        )
        Log.i(TAG, "Vector backfill complete: $stats")
        stats
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

    data class BackfillStats(val total: Int, val processed: Int, val failed: Int, val durationMs: Long) {
        companion object {
            fun empty(): BackfillStats = BackfillStats(total = 0, processed = 0, failed = 0, durationMs = 0)
        }
    }

    private companion object {
        const val TAG = "VectorBackfill"
        const val DEFAULT_BATCH_SIZE = 16
    }
}
