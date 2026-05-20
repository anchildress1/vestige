package dev.anchildress1.vestige.patterns

import android.util.Log
import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.PatternState
import dev.anchildress1.vestige.storage.EmbeddingClustering
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.PatternStore
import dev.anchildress1.vestige.storage.VocabClusterLabeler
import dev.anchildress1.vestige.storage.VocabClustersCodec
import dev.anchildress1.vestige.storage.callClosingThreadResources
import dev.anchildress1.vestige.storage.vocabRootTokenOrNull
import io.objectbox.BoxStore

/**
 * Stamps `vocabClustersJson` on each ACTIVE `VOCAB_FREQUENCY` row. Runs as a second pass after
 * detection. The evidence hash covers only entries with a usable vector so async backfill
 * (null → vector) flips the hash and triggers reclustering. When the supporting set drops
 * below the floor, any previously-stamped JSON is cleared so the UI doesn't keep showing stale
 * clusters. Writes happen inside a tx that re-reads the lifecycle state so a concurrent
 * SNOOZED/DROPPED transition wins over this stamp.
 */
internal class PatternVocabClusterUpdater(private val boxStore: BoxStore, private val patternStore: PatternStore) {

    fun stampAll() {
        patternStore.findActive()
            .asSequence()
            .filter { it.kind == PatternKind.VOCAB_FREQUENCY }
            .mapNotNull { pattern -> buildClustersFor(pattern)?.let { pattern to it } }
            .forEach { (pattern, json) -> writeClustersInTx(pattern.patternId, json) }
    }

    @Suppress("ReturnCount") // Floor / no-op / missing-token each take a distinct early-exit path.
    private fun buildClustersFor(pattern: PatternEntity): String? {
        val supporting = boxStore.callClosingThreadResources { pattern.supportingEntries.toList() }
        val vectored = supporting.filter { it.hasUsableVector() }
        if (vectored.size < EmbeddingClustering.MIN_SUPPORTING_ENTRIES) {
            // Support dropped below the floor (or vectors not yet backfilled). Clear any stale
            // payload so the UI doesn't keep showing a now-invalid distribution.
            return if (pattern.vocabClustersJson.isNotBlank()) "" else null
        }
        // Hash only the vectored set — when the backfill worker stamps a vector on a previously
        // null member, the set grows and the hash changes, triggering re-cluster.
        val evidenceHash = VocabClustersCodec.evidenceHashOf(vectored.map { it.id })
        if (VocabClustersCodec.evidenceHashIn(pattern.vocabClustersJson) == evidenceHash) return null
        val rootToken = vocabRootTokenOrNull(pattern.signatureJson) ?: run {
            Log.e(
                TAG,
                "vocab-frequency pattern missing token pid=${pattern.patternId} sigLen=${pattern.signatureJson.length}",
            )
            return null
        }
        return EmbeddingClustering.cluster(vectored)
            .takeIf { it.isNotEmpty() }
            ?.let { clusters ->
                VocabClustersCodec.encode(
                    clusters = clusters.map { VocabClusterLabeler.label(it, rootToken) },
                    evidenceHash = evidenceHash,
                )
            }
    }

    private fun writeClustersInTx(patternId: String, json: String) {
        boxStore.runInTx {
            val fresh = patternStore.findByPatternId(patternId) ?: return@runInTx
            if (fresh.state != PatternState.ACTIVE) return@runInTx
            if (fresh.vocabClustersJson == json) return@runInTx
            fresh.vocabClustersJson = json
            patternStore.put(fresh)
        }
    }

    /** Mirrors the upstream filter in [EmbeddingClustering] — null + zero-norm + NaN/Inf excluded. */
    @Suppress("ReturnCount") // Three guards (null / empty / non-finite) each map to false distinctly.
    private fun EntryEntity.hasUsableVector(): Boolean {
        val v = vector ?: return false
        if (v.isEmpty()) return false
        var sumSq = 0.0
        for (f in v) {
            if (!f.isFinite()) return false
            sumSq += f.toDouble() * f.toDouble()
        }
        return sumSq > 0.0
    }

    private companion object {
        const val TAG: String = "VestigeVocabUpdater"
    }
}
