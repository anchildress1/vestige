package dev.anchildress1.vestige.patterns

import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.PatternState
import dev.anchildress1.vestige.storage.EmbeddingClustering
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.PatternStore
import dev.anchildress1.vestige.storage.VocabClusterLabeler
import dev.anchildress1.vestige.storage.VocabClustersCodec
import dev.anchildress1.vestige.storage.callClosingThreadResources
import io.objectbox.BoxStore
import org.json.JSONObject

/**
 * Second-pass enrichment over each ACTIVE `VOCAB_FREQUENCY` pattern: cluster the supporting
 * entries' embeddings by cosine similarity and stamp the labeled result on
 * `PatternEntity.vocabClustersJson`. Lives outside the orchestrator so the cluster-update
 * logic can evolve (e.g. different distance cuts per pattern kind, future HDBSCAN) without
 * touching the save-flow contract.
 *
 * Idempotent: re-running on unchanged evidence is a no-op (skipped before opening the tx).
 * Re-reads the row inside the write tx to avoid clobbering a concurrent state transition out
 * of ACTIVE — ADR-003 §step 6 requires the write to honor the current lifecycle state.
 */
internal class PatternVocabClusterUpdater(
    private val boxStore: BoxStore,
    private val patternStore: PatternStore,
) {

    fun stampAll() {
        patternStore.findActive()
            .asSequence()
            .filter { it.kind == PatternKind.VOCAB_FREQUENCY }
            .mapNotNull { pattern -> buildClustersFor(pattern)?.let { pattern to it } }
            .forEach { (pattern, json) -> writeClustersInTx(pattern.patternId, json) }
    }

    private fun buildClustersFor(pattern: PatternEntity): String? {
        val supporting = boxStore.callClosingThreadResources { pattern.supportingEntries.toList() }
        if (supporting.size < EmbeddingClustering.MIN_SUPPORTING_ENTRIES) return null
        return EmbeddingClustering.cluster(supporting)
            .takeIf { it.isNotEmpty() }
            ?.let { clusters ->
                val rootToken = runCatching {
                    JSONObject(pattern.signatureJson).optString("token", "")
                }.getOrDefault("")
                VocabClustersCodec.encode(clusters.map { VocabClusterLabeler.label(it, rootToken) })
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
}
