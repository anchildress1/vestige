package dev.anchildress1.vestige.patterns

import android.util.Log
import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.PatternState
import dev.anchildress1.vestige.storage.EmbeddingClustering
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.PatternStore
import dev.anchildress1.vestige.storage.VocabClusterLabeler
import dev.anchildress1.vestige.storage.VocabClustersCodec
import dev.anchildress1.vestige.storage.callClosingThreadResources
import dev.anchildress1.vestige.storage.vocabRootTokenOrNull
import io.objectbox.BoxStore

/**
 * Stamps `vocabClustersJson` on each ACTIVE `VOCAB_FREQUENCY` row. Runs as a second pass after
 * detection; skipped on every commit where the pattern's supporting set hasn't changed (cheap
 * SHA hash of sorted supporting ids vs the persisted envelope's `evidence_hash`). On a real
 * change: re-clusters, re-encodes, writes inside a tx that re-reads the lifecycle state so a
 * concurrent SNOOZED/DROPPED transition wins over this stamp.
 */
internal class PatternVocabClusterUpdater(private val boxStore: BoxStore, private val patternStore: PatternStore) {

    fun stampAll() {
        patternStore.findActive()
            .asSequence()
            .filter { it.kind == PatternKind.VOCAB_FREQUENCY }
            .mapNotNull { pattern -> buildClustersFor(pattern)?.let { pattern to it } }
            .forEach { (pattern, json) -> writeClustersInTx(pattern.patternId, json) }
    }

    @Suppress("ReturnCount") // Three early bails (floor / no-op / missing token) each have their own reason.
    private fun buildClustersFor(pattern: PatternEntity): String? {
        val supporting = boxStore.callClosingThreadResources { pattern.supportingEntries.toList() }
        if (supporting.size < EmbeddingClustering.MIN_SUPPORTING_ENTRIES) return null
        val evidenceHash = VocabClustersCodec.evidenceHashOf(supporting.map { it.id })
        if (VocabClustersCodec.evidenceHashIn(pattern.vocabClustersJson) == evidenceHash) return null
        val rootToken = vocabRootTokenOrNull(pattern.signatureJson) ?: run {
            Log.e(
                TAG,
                "vocab-frequency pattern missing token pid=${pattern.patternId} sigLen=${pattern.signatureJson.length}",
            )
            return null
        }
        return EmbeddingClustering.cluster(supporting)
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

    private companion object {
        const val TAG: String = "VestigeVocabUpdater"
    }
}
