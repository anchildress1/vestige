package dev.anchildress1.vestige.storage

/** Deterministic label + description + centroid-example derivation for an [EmbeddingClustering.Cluster]. */
object VocabClusterLabeler {

    /** Hard cap on label length so the screen layout doesn't have to truncate at render. */
    const val MAX_LABEL_CHARS: Int = 24

    /** How many tokens to surface in the description. */
    const val DESCRIPTION_TOKEN_LIMIT: Int = 3

    /**
     * Build a [VocabCluster] from a clustering result and the pattern's root [rootToken] (the
     * shared word the cluster is a framing of — never repeated in the label).
     *
     * Returns a [VocabCluster] whose `memberEntryIds` is sorted ascending — matches the input
     * order [EmbeddingClustering] produces, so the SHA-256 stamp stays stable.
     */
    fun label(cluster: EmbeddingClustering.Cluster, rootToken: String): VocabCluster {
        val tokens = topDistinctiveTokens(cluster.members, rootToken)
        return VocabCluster.of(
            members = cluster.members.map { it.id },
            label = renderLabel(tokens),
            description = renderDescription(cluster.members.size, tokens),
            exampleEntryId = exampleEntryId(cluster.members),
        )
    }

    private fun renderLabel(tokens: List<String>): String {
        if (tokens.isEmpty()) return "framings"
        val joined = tokens.joinToString(", ")
        return if (joined.length <= MAX_LABEL_CHARS) joined else joined.take(MAX_LABEL_CHARS - 1) + "…"
    }

    private fun renderDescription(memberCount: Int, tokens: List<String>): String {
        val noun = if (memberCount == 1) "entry" else "entries"
        return when {
            tokens.isEmpty() -> "$memberCount $noun · framings unavailable"
            else -> "$memberCount $noun · framings: ${tokens.joinToString(", ")}"
        }
    }

    /**
     * Distinct tone words of the cluster, ranked by frequency. Each member contributes its single
     * model-emitted [EntryEntity.vocabularyWord], canonicalized via
     * [PatternSignature.canonicalVocabToken]. Excludes [rootToken] (already canonical — it's the
     * cluster's dominant word, repeating it is noise). Tie-breaks alphabetically so the picked
     * tokens are stable across runs.
     */
    private fun topDistinctiveTokens(members: List<EntryEntity>, rootToken: String): List<String> {
        val counts = mutableMapOf<String, Int>()
        members
            .asSequence()
            .mapNotNull { it.vocabularyWord?.takeIf { word -> word.isNotBlank() } }
            .map { PatternSignature.canonicalVocabToken(it) }
            .filter { it != rootToken }
            .forEach { token -> counts.merge(token, 1) { a, b -> a + b } }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(DESCRIPTION_TOKEN_LIMIT)
            .map { it.key }
    }

    /**
     * Member whose vector is closest to the centroid (cosine), tiebreak by ascending id.
     * Returns the first member's id if the cluster has no vectored entries — defensive only:
     * upstream filters null vectors before clustering.
     */
    private fun exampleEntryId(members: List<EntryEntity>): Long {
        val vectored = members.filter { it.vector != null }
        if (vectored.isEmpty()) return members.first().id
        val dim = vectored.first().vector!!.size
        val centroid = FloatArray(dim)
        for (e in vectored) {
            val v = l2Normalize(e.vector!!)
            for (i in 0 until dim) centroid[i] += v[i]
        }
        for (i in 0 until dim) centroid[i] /= vectored.size
        val centroidNorm = l2Normalize(centroid)

        return vectored
            .map { it.id to similarity(centroidNorm, l2Normalize(it.vector!!)) }
            .sortedWith(
                compareByDescending<Pair<Long, Double>> { it.second }.thenBy { it.first },
            )
            .first()
            .first
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sumSq = 0.0
        for (v in vector) sumSq += v.toDouble() * v.toDouble()
        val norm = kotlin.math.sqrt(sumSq).toFloat()
        if (norm == 0f) return vector.copyOf()
        return FloatArray(vector.size) { vector[it] / norm }
    }

    private fun similarity(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        for (i in a.indices) dot += a[i].toDouble() * b[i].toDouble()
        return dot
    }
}
