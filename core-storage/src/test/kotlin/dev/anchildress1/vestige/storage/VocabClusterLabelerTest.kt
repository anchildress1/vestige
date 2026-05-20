package dev.anchildress1.vestige.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabClusterLabelerTest {

    @Test
    fun `label uses the top distinctive token excluding the root`() {
        val cluster = clusterOf(
            members = listOf(
                "I am exhausted and drained from work" to 1L,
                "Exhausted again today, drained completely" to 2L,
                "Wiped out, exhausted, drained" to 3L,
            ),
        )

        val result = VocabClusterLabeler.label(cluster, rootToken = "tired")

        // "exhausted" and "drained" each appear 3×; alphabetical tiebreak picks them first
        // (drained, exhausted). The third slot is a single-occurrence tie between
        // "completely", "wiped", and "work" — alpha-asc picks "completely", which pushes the
        // joined length past MAX_LABEL_CHARS=24 → "drained, exhausted, com…".
        assertEquals("drained, exhausted, com…", result.label)
    }

    @Test
    fun `label excludes the root token even when it appears`() {
        val cluster = clusterOf(
            members = listOf(
                "tired tired tired exhausted" to 1L,
                "tired exhausted exhausted" to 2L,
                "tired so tired exhausted" to 3L,
            ),
        )

        val result = VocabClusterLabeler.label(cluster, rootToken = "tired")

        // "tired" is excluded — it's the framing-of, not the framing itself.
        assertTrue("label must not include the root token", "tired" !in result.label)
        assertTrue("label must surface exhausted", "exhausted" in result.label)
    }

    @Test
    fun `description includes member count and top tokens`() {
        val cluster = clusterOf(
            members = listOf(
                "sluggish foggy" to 1L,
                "sluggish brain foggy" to 2L,
                "foggy sluggish" to 3L,
                "burnt out foggy" to 4L,
            ),
        )

        val result = VocabClusterLabeler.label(cluster, rootToken = "tired")

        assertEquals("4 entries · framings: foggy, sluggish, brain", result.description)
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
    fun `label and description survive when no distinctive tokens are available`() {
        // All text is the root word + stopwords.
        val cluster = clusterOf(
            members = listOf(
                "tired and tired" to 1L,
                "very tired" to 2L,
                "still tired today" to 3L,
            ),
        )

        val result = VocabClusterLabeler.label(cluster, rootToken = "tired")

        assertEquals("framings", result.label)
        assertTrue(result.description.endsWith("framings unavailable"))
    }

    @Test
    fun `example entry is the member closest to the cluster centroid`() {
        // Three members near axis 0; the middle one is also nearest to the centroid because
        // its perturbation cancels the others'.
        val members = listOf(
            entry(id = 10L, text = "a", vector = nearAxis(axis = 0, perturb = -0.1)),
            entry(id = 11L, text = "b", vector = nearAxis(axis = 0, perturb = 0.0)),
            entry(id = 12L, text = "c", vector = nearAxis(axis = 0, perturb = 0.1)),
            entry(id = 13L, text = "d", vector = nearAxis(axis = 0, perturb = 0.0)),
            entry(id = 14L, text = "e", vector = nearAxis(axis = 0, perturb = 0.0)),
            entry(id = 15L, text = "f", vector = nearAxis(axis = 0, perturb = 0.0)),
        )
        val cluster = EmbeddingClustering.Cluster.of(members)

        val result = VocabClusterLabeler.label(cluster, rootToken = "")

        // Three members tied at the centroid (perturb=0); tiebreak on ascending id picks 11.
        assertEquals(11L, result.exampleEntryId)
    }

    @Test
    fun `member entry ids preserve cluster input order`() {
        // The clustering layer outputs members sorted by id ascending. The labeler must not
        // re-sort or shuffle — the clusterId hash assumes the input order.
        val cluster = clusterOf(
            members = listOf("a" to 5L, "b" to 7L, "c" to 11L),
        )

        val result = VocabClusterLabeler.label(cluster, rootToken = "tired")

        assertEquals(listOf(5L, 7L, 11L), result.memberEntryIds)
    }

    @Test
    fun `label respects 24-char cap with ellipsis`() {
        // Three very long tokens, comma-joined, will blow the cap.
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

    @Test
    fun `stopwords are dropped from the label`() {
        val cluster = clusterOf(
            members = listOf(
                "I have been exhausted just today" to 1L,
                "Just so exhausted" to 2L,
                "Today I have exhausted everything" to 3L,
            ),
        )

        val result = VocabClusterLabeler.label(cluster, rootToken = "tired")

        // "exhausted" is the only non-stopword that survives.
        assertEquals("exhausted, everything", result.label)
    }

    private fun clusterOf(members: List<Pair<String, Long>>): EmbeddingClustering.Cluster =
        EmbeddingClustering.Cluster.of(
            members.map { (text, id) -> entry(id = id, text = text, vector = nearAxis(0, 0.0)) },
        )

    private fun entry(id: Long, text: String, vector: FloatArray?): EntryEntity =
        EntryEntity(entryText = text, timestampEpochMs = id * 1000L).also {
            it.id = id
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
