package dev.anchildress1.vestige.storage

/**
 * Deterministic agglomerative clustering (average-linkage cosine, sorted by entryId for
 * input-order stability) for vocab-drift enrichment. Same evidence ⇒ identical clusters.
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
    fun cluster(members: List<EntryEntity>, maxCosineDistance: Double = DEFAULT_MAX_COSINE_DISTANCE): List<Cluster> {
        require(maxCosineDistance in 0.0..2.0) {
            "maxCosineDistance must be in [0,2] (got $maxCosineDistance)"
        }
        val vectored = members
            .asSequence()
            .filter { it.vector != null }
            // Drop pathological vectors so they can't poison the metric. Zero-norm produces
            // 1.0 distance to everything (singleton spam); NaN/Inf break the comparator.
            .filter { it.vector!!.isUsableVector() }
            .sortedBy { it.id } // input-order stability
            .toList()
        if (vectored.size < MIN_SUPPORTING_ENTRIES) return emptyList()
        val dim = vectored.first().vector!!.size
        require(vectored.all { it.vector!!.size == dim }) {
            "all input vectors must share the same dimension (saw $dim and ${vectored.first {
                it.vector!!.size != dim
            }.vector!!.size})"
        }

        val normalized = vectored.map { l2Normalize(it.vector!!) }
        val labels = IntArray(vectored.size) { it }

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

    private fun closestPair(normalized: List<FloatArray>, labels: IntArray): Triple<Int, Int, Double>? {
        var bestI = -1
        var bestJ = -1
        var bestDist = Double.MAX_VALUE
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
            .map { Cluster.of(it) }
            .sortedWith(
                compareByDescending<Cluster> { it.members.size }
                    .thenBy { it.members.first().id },
            )
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sumSq = 0.0
        for (v in vector) sumSq += v.toDouble() * v.toDouble()
        val norm = kotlin.math.sqrt(sumSq).toFloat()
        // Pre-filtered upstream by [isUsableVector] — norm > 0, all finite — so this is safe.
        return FloatArray(vector.size) { vector[it] / norm }
    }

    private fun FloatArray.isUsableVector(): Boolean {
        if (isEmpty()) return false
        var sumSq = 0.0
        var nonFinite = false
        for (v in this) {
            if (!v.isFinite()) {
                nonFinite = true
                break
            }
            sumSq += v.toDouble() * v.toDouble()
        }
        return !nonFinite && sumSq > 0.0
    }

    private fun cosineDistance(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "vector dim mismatch: ${a.size} vs ${b.size}" }
        // Both vectors are L2-normalized upstream, so cosine similarity = dot product.
        var dot = 0.0
        for (i in a.indices) dot += a[i].toDouble() * b[i].toDouble()
        return 1.0 - dot
    }

    /**
     * One clustering result. Constructor is private so [clusterId] always matches the SHA-256
     * hash of the sorted member ids — the content-addressable invariant the orchestrator's
     * dirty-bit gate relies on.
     */
    @Suppress("DataClassPrivateConstructor")
    data class Cluster private constructor(val clusterId: String, val members: List<EntryEntity>) {
        internal companion object {
            internal fun of(members: List<EntryEntity>): Cluster {
                require(members.isNotEmpty()) { "EmbeddingClustering.Cluster.members must be non-empty" }
                val sorted = members.sortedBy { it.id }
                return Cluster(
                    clusterId = VocabCluster.sha256Hex(sorted.map { it.id }),
                    members = sorted,
                )
            }
        }
    }
}
