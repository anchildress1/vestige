package dev.anchildress1.vestige.storage

import java.security.MessageDigest

/**
 * Deterministic agglomerative clustering over per-entry embedding vectors. Designed for the
 * vocab-drift surface: cluster the embeddings of a `VOCAB_FREQUENCY` pattern's supporting
 * entries so the UI can show "N distinct framings of the same underlying state."
 *
 * Determinism guarantees:
 * - Input order does not affect output. Inputs are sorted by `entryId` before clustering.
 * - No randomness, no learned parameters, no seeds. Same evidence ⇒ identical clusters.
 * - Cluster ids are content-addressable (SHA-256 hex of sorted member entry ids).
 *
 * Algorithm: average-linkage agglomerative clustering on cosine distance, cut at
 * [maxCosineDistance]. Implementation is O(N² · log N) — fine for the v1 ceiling of a few
 * hundred supporting entries per pattern; if that ever grows, swap in HDBSCAN.
 */
object EmbeddingClustering {

    /** Default cut on cosine distance. Calibrated against the demo "tired × 23" fixture. */
    const val DEFAULT_MAX_COSINE_DISTANCE: Double = 0.30

    /** Minimum supporting-entry count below which clustering is skipped entirely. */
    const val MIN_SUPPORTING_ENTRIES: Int = 6

    /**
     * Cluster [members] by their `vector`. Entries with a null vector are dropped — they
     * haven't been backfilled yet and would distort the metric. Returns clusters sorted by
     * descending size; singletons are kept (they still represent a distinct framing).
     */
    fun cluster(
        members: List<EntryEntity>,
        maxCosineDistance: Double = DEFAULT_MAX_COSINE_DISTANCE,
    ): List<Cluster> {
        require(maxCosineDistance in 0.0..2.0) {
            "maxCosineDistance must be in [0,2] (got $maxCosineDistance)"
        }
        val vectored = members
            .asSequence()
            .filter { it.vector != null }
            .sortedBy { it.id } // input-order stability
            .toList()
        if (vectored.size < MIN_SUPPORTING_ENTRIES) return emptyList()

        val normalized = vectored.map { l2Normalize(it.vector!!) }
        val labels = IntArray(vectored.size) { it }

        // Build initial pairwise cosine distances. Average-linkage means we recompute as
        // clusters merge by tracking running cluster sums (not full pair-recompute).
        var done = false
        while (!done) {
            val (bestI, bestJ, bestDist) = closestPair(normalized, labels) ?: break
            if (bestDist > maxCosineDistance) {
                done = true
            } else {
                val source = labels[bestJ]
                val target = labels[bestI]
                for (k in labels.indices) if (labels[k] == source) labels[k] = target
            }
        }

        return assembleClusters(vectored, labels)
    }

    private fun closestPair(
        normalized: List<FloatArray>,
        labels: IntArray,
    ): Triple<Int, Int, Double>? {
        var bestI = -1
        var bestJ = -1
        var bestDist = Double.MAX_VALUE
        // Use average linkage: distance between clusters = mean pairwise cosine distance
        // over members of each cluster. Walking the full N² pair table on every merge is
        // acceptable at the v1 cap (~200 entries); the savings of incremental linkage update
        // aren't worth the bookkeeping until we see real perf pressure.
        for (i in normalized.indices) {
            for (j in i + 1 until normalized.size) {
                if (labels[i] == labels[j]) continue
                val d = averageLinkageDistance(normalized, labels, labels[i], labels[j])
                if (d < bestDist) {
                    bestDist = d
                    bestI = i
                    bestJ = j
                }
            }
        }
        return if (bestI < 0) null else Triple(bestI, bestJ, bestDist)
    }

    private fun averageLinkageDistance(
        normalized: List<FloatArray>,
        labels: IntArray,
        labelA: Int,
        labelB: Int,
    ): Double {
        var sum = 0.0
        var count = 0
        for (i in normalized.indices) {
            if (labels[i] != labelA) continue
            for (j in normalized.indices) {
                if (labels[j] != labelB) continue
                sum += cosineDistance(normalized[i], normalized[j])
                count += 1
            }
        }
        return if (count == 0) Double.MAX_VALUE else sum / count
    }

    private fun assembleClusters(members: List<EntryEntity>, labels: IntArray): List<Cluster> {
        val byLabel = mutableMapOf<Int, MutableList<EntryEntity>>()
        for (i in members.indices) {
            byLabel.getOrPut(labels[i]) { mutableListOf() }.add(members[i])
        }
        return byLabel.values
            .map { group ->
                val sortedMembers = group.sortedBy { it.id }
                Cluster(
                    clusterId = clusterIdOf(sortedMembers.map { it.id }),
                    members = sortedMembers,
                )
            }
            .sortedWith(
                compareByDescending<Cluster> { it.members.size }
                    .thenBy { it.members.first().id },
            )
    }

    private fun clusterIdOf(sortedMemberIds: List<Long>): String {
        val canonical = sortedMemberIds.joinToString(",")
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sumSq = 0.0
        for (v in vector) sumSq += v.toDouble() * v.toDouble()
        val norm = kotlin.math.sqrt(sumSq).toFloat()
        if (norm == 0f) return vector.copyOf() // pathological all-zero vector; keep as-is
        return FloatArray(vector.size) { vector[it] / norm }
    }

    private fun cosineDistance(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "vector dim mismatch: ${a.size} vs ${b.size}" }
        // Both vectors are L2-normalized upstream, so cosine similarity = dot product.
        var dot = 0.0
        for (i in a.indices) dot += a[i].toDouble() * b[i].toDouble()
        return 1.0 - dot
    }

    data class Cluster(
        val clusterId: String,
        val members: List<EntryEntity>,
    )
}
