package dev.anchildress1.vestige.storage

import android.util.Log
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive

/**
 * One-shot, idempotent vector backfill: embeds and persists any [EntryEntity] whose
 * [EntryEntity.vector] is null. Cooperative with cancellation — if the parent scope is cancelled
 * mid-pass, partial progress survives and the remaining null-vector rows get picked up on the
 * next invocation.
 */
class VectorBackfillWorker(private val boxStore: BoxStore, private val embedder: suspend (String) -> FloatArray) {

    /**
     * Cheap-ish presence check: returns true iff at least one entry has a null vector. Callers
     * use this to gate expensive setup (artifact SHA-256 verification, embedder construction)
     * so a cold start with no pending work pays no IO.
     */
    fun hasPendingWork(): Boolean =
        boxStore.boxFor<EntryEntity>().query().isNull(EntryEntity_.vector).build().use { it.count() > 0 }

    suspend fun backfill(): BackfillStats = coroutineScope {
        val entryBox = boxStore.boxFor<EntryEntity>()
        val pending: List<EntryEntity> = entryBox.query().isNull(EntryEntity_.vector).build().use { it.find() }
        if (pending.isEmpty()) {
            return@coroutineScope BackfillStats.empty()
        }
        Log.i(TAG, "Vector backfill: ${pending.size} entries pending")
        val started = System.currentTimeMillis()
        var processed = 0
        var failed = 0
        for (entry in pending) {
            ensureActive()
            try {
                val vector = embedder(entry.entryText)
                check(vector.size.toLong() == EntryEntity.EMBEDDING_DIMENSIONS) {
                    "Embedder returned ${vector.size}-d vector; expected ${EntryEntity.EMBEDDING_DIMENSIONS}"
                }
                entry.vector = vector
                entryBox.put(entry)
                processed++
            } catch (cancel: CancellationException) {
                // Coroutine cancellation must not look like an embedder failure — rethrow so the
                // outer scope unwinds cleanly and partial progress already committed stays intact.
                throw cancel
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                failed++
                Log.w(TAG, "Vector backfill failed for entry ${entry.id}", error)
            }
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

    data class BackfillStats(val total: Int, val processed: Int, val failed: Int, val durationMs: Long) {
        companion object {
            fun empty(): BackfillStats = BackfillStats(total = 0, processed = 0, failed = 0, durationMs = 0)
        }
    }

    private companion object {
        const val TAG = "VectorBackfill"
    }
}
