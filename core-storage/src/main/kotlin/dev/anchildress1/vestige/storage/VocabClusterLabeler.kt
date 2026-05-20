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
     * Token-frequency rank across the cluster's `entryText` corpus. Excludes [rootToken] (the
     * cluster is a framing *of* the root word — repeating it is noise). Excludes stopwords.
     * Tie-breaks by alphabetical order so the picked tokens are stable across runs.
     */
    private fun topDistinctiveTokens(members: List<EntryEntity>, rootToken: String): List<String> {
        val rootLower = rootToken.lowercase()
        val counts = mutableMapOf<String, Int>()
        members
            .asSequence()
            .flatMap { tokenize(it.entryText) }
            .filter { it != rootLower && it !in STOPWORDS }
            .forEach { token -> counts.merge(token, 1) { a, b -> a + b } }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(DESCRIPTION_TOKEN_LIMIT)
            .map { it.key }
    }

    private fun tokenize(text: String): Sequence<String> = TOKEN_SPLIT_REGEX
        .split(text.lowercase())
        .asSequence()
        .filter { it.length >= MIN_TOKEN_CHARS }
        .filter { it.all(Char::isLetter) }

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

    private const val MIN_TOKEN_CHARS: Int = 3
    private val TOKEN_SPLIT_REGEX = Regex("""[\s.,;:!?\-'"()/]+""")

    // Tiny stop-list — the v1 pattern surface speaks American English; a small list keeps the
    // dependency footprint zero (no external NLP libs). Tokens shorter than MIN_TOKEN_CHARS
    // are already filtered.
    private val STOPWORDS: Set<String> = setOf(
        "the", "and", "but", "for", "with", "from", "into", "onto", "this", "that", "those",
        "these", "you", "your", "yours", "are", "was", "were", "been", "being", "have", "had",
        "has", "not", "out", "all", "any", "some", "very", "just", "still", "even", "again",
        "more", "most", "much", "many", "really", "today", "tomorrow", "yesterday", "now",
        "then", "when", "where", "what", "which", "who", "how", "why", "about", "after",
        "before", "during", "over", "under", "while", "until", "than", "though", "since",
        "because", "would", "could", "should", "will", "going", "get", "got", "make", "made",
        "feel", "feels", "felt", "feeling", "kind", "sort", "thing", "things", "stuff",
    )
}
