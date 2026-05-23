package dev.anchildress1.vestige.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabClusterLabelerTest {

    @Test
    fun `framings are the distinct tone words ranked by frequency`() {
        // numb 3×, foggy 2×, edgy 1×. Freq desc → numb, foggy, edgy (joins to 17 chars, no cap).
        val cluster = clusterOf(
            members = listOf(
                "numb" to 1L,
                "numb" to 2L,
                "numb" to 3L,
                "foggy" to 4L,
                "foggy" to 5L,
                "edgy" to 6L,
            ),
        )

        val result = VocabClusterLabeler.label(cluster, rootToken = "tired")

        assertEquals("numb, foggy, edgy", result.label)
    }

    @Test
    fun `frequency ties break alphabetically`() {
        // foggy 2×, sluggish 2×, brain 1×. Tie at 2× → alpha asc (foggy, sluggish), then brain.
        val cluster = clusterOf(
            members = listOf(
                "sluggish" to 1L,
                "foggy" to 2L,
                "foggy" to 3L,
                "sluggish" to 4L,
                "brain" to 5L,
            ),
        )

        val result = VocabClusterLabeler.label(cluster, rootToken = "tired")

        assertEquals("5 entries · framings: foggy, sluggish, brain", result.description)
    }

    @Test
    fun `the cluster root tone word is excluded from framings`() {
        // Member tone words include the root "tired" — it's the framing-of, never a framing.
        val cluster = clusterOf(
            members = listOf(
                "tired" to 1L,
                "exhausted" to 2L,
                "exhausted" to 3L,
            ),
        )

        val result = VocabClusterLabeler.label(cluster, rootToken = "tired")

        assertTrue("label must not include the root token", "tired" !in result.label)
        assertEquals("exhausted", result.label)
    }

    @Test
    fun `tone words are canonicalized before counting`() {
        // "Exhausted" and "exhausted" case-fold to one token; "jitters" singularizes to "jitter".
        val cluster = clusterOf(
            members = listOf(
                "Exhausted" to 1L,
                "exhausted" to 2L,
                "jitters" to 3L,
            ),
        )

        val result = VocabClusterLabeler.label(cluster, rootToken = "tired")

        assertEquals("exhausted, jitter", result.label)
    }

    @Test
    fun `multi-word tone phrases kebab intact instead of splitting`() {
        // The tone word is a single label, not a sentence — "burnt out" canonicalizes whole.
        val cluster = clusterOf(
            members = listOf(
                "burnt out" to 1L,
                "burnt out" to 2L,
            ),
        )

        val result = VocabClusterLabeler.label(cluster, rootToken = "tired")

        assertEquals("burnt-out", result.label)
    }

    @Test
    fun `description uses singular noun for single-entry cluster`() {
        val cluster = clusterOf(members = listOf("exhausted" to 1L))

        val result = VocabClusterLabeler.label(cluster, rootToken = "tired")

        assertTrue(
            "description should use 'entry' (singular) for 1 member, got: ${result.description}",
            result.description.startsWith("1 entry"),
        )
    }

    @Test
    fun `framings unavailable when members have no usable tone words`() {
        // Members are null, blank, or duplicate-of-root → nothing distinctive survives.
        val members = listOf(
            entry(id = 1L, vocabularyWord = null, vector = nearAxis(0, 0.0)),
            entry(id = 2L, vocabularyWord = "  ", vector = nearAxis(0, 0.0)),
            entry(id = 3L, vocabularyWord = "tired", vector = nearAxis(0, 0.0)),
        )
        val cluster = EmbeddingClustering.Cluster.of(members)

        val result = VocabClusterLabeler.label(cluster, rootToken = "tired")

        assertEquals("framings", result.label)
        assertTrue(result.description.endsWith("framings unavailable"))
    }

    @Test
    fun `example entry is the member closest to the cluster centroid`() {
        // Three members near axis 0; the middle one is also nearest to the centroid because
        // its perturbation cancels the others'.
        val members = listOf(
            entry(id = 10L, vocabularyWord = "a", vector = nearAxis(axis = 0, perturb = -0.1)),
            entry(id = 11L, vocabularyWord = "b", vector = nearAxis(axis = 0, perturb = 0.0)),
            entry(id = 12L, vocabularyWord = "c", vector = nearAxis(axis = 0, perturb = 0.1)),
            entry(id = 13L, vocabularyWord = "d", vector = nearAxis(axis = 0, perturb = 0.0)),
            entry(id = 14L, vocabularyWord = "e", vector = nearAxis(axis = 0, perturb = 0.0)),
            entry(id = 15L, vocabularyWord = "f", vector = nearAxis(axis = 0, perturb = 0.0)),
        )
        val cluster = EmbeddingClustering.Cluster.of(members)

        val result = VocabClusterLabeler.label(cluster, rootToken = "")

        // Three members tied at the centroid (perturb=0); tiebreak on ascending id picks 11.
        assertEquals(11L, result.exampleEntryId)
    }

    @Test
    fun `member entry ids are sorted ascending`() {
        // VocabCluster.of sorts ids before hashing; the labeler must respect that invariant so
        // the persisted clusterId matches a re-derivation from the same evidence.
        val cluster = clusterOf(
            members = listOf("a" to 11L, "b" to 5L, "c" to 7L),
        )

        val result = VocabClusterLabeler.label(cluster, rootToken = "tired")

        assertEquals(listOf(5L, 7L, 11L), result.memberEntryIds)
    }

    @Test
    fun `label respects 24-char cap with ellipsis`() {
        // Three very long tone words, comma-joined, will blow the cap.
        val cluster = clusterOf(
            members = listOf(
                "supercalifragilistic" to 1L,
                "antidisestablishmentarianism" to 2L,
                "pneumonoultramicroscopic" to 3L,
            ),
        )

        val result = VocabClusterLabeler.label(cluster, rootToken = "tired")

        assertTrue(
            "label must be ≤${VocabClusterLabeler.MAX_LABEL_CHARS} chars (got ${result.label.length})",
            result.label.length <= VocabClusterLabeler.MAX_LABEL_CHARS,
        )
        assertTrue("truncated label must end with ellipsis", result.label.endsWith("…"))
    }

    private fun clusterOf(members: List<Pair<String, Long>>): EmbeddingClustering.Cluster =
        EmbeddingClustering.Cluster.of(
            members.map { (word, id) -> entry(id = id, vocabularyWord = word, vector = nearAxis(0, 0.0)) },
        )

    private fun entry(id: Long, vocabularyWord: String?, vector: FloatArray?): EntryEntity =
        EntryEntity(entryText = "entry-$id", timestampEpochMs = id * 1000L).also {
            it.id = id
            it.vocabularyWord = vocabularyWord
            it.vector = vector
        }

    private fun nearAxis(axis: Int, perturb: Double): FloatArray {
        val v = FloatArray(EMBED_DIM)
        v[axis] = 1.0f
        if (perturb != 0.0) v[(axis + 1) % EMBED_DIM] = perturb.toFloat()
        return v
    }

    private companion object {
        const val EMBED_DIM: Int = 8
    }
}
