package dev.anchildress1.vestige.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingClusteringTest {

    @Test
    fun `fewer than the minimum supporting entries returns no clusters`() {
        // MIN_SUPPORTING_ENTRIES is the v1 floor — below it the surface is meaningless.
        val members = (1..EmbeddingClustering.MIN_SUPPORTING_ENTRIES - 1).map {
            entry(id = it.toLong(), vector = randomVector(seed = it))
        }

        val clusters = EmbeddingClustering.cluster(members)

        assertTrue(clusters.isEmpty())
    }

    @Test
    fun `entries with null vectors are dropped from the input`() {
        val members = (1..EmbeddingClustering.MIN_SUPPORTING_ENTRIES + 2).map { i ->
            entry(id = i.toLong(), vector = if (i % 2 == 0) null else randomVector(seed = i))
        }

        val clusters = EmbeddingClustering.cluster(members)

        // Half were null → 4 left, below the floor → no clusters.
        assertTrue(clusters.isEmpty())
    }

    @Test
    fun `three well-separated groups produce three clusters`() {
        val groupA = (1L..7L).map { entry(id = it, vector = nearVector(axis = 0, jitter = it * 0.001)) }
        val groupB = (10L..16L).map { entry(id = it, vector = nearVector(axis = 1, jitter = it * 0.001)) }
        val groupC = (20L..26L).map { entry(id = it, vector = nearVector(axis = 2, jitter = it * 0.001)) }

        val clusters = EmbeddingClustering.cluster(groupA + groupB + groupC)

        assertEquals(3, clusters.size)
        // Sorted by size descending — all three are 7 so secondary sort by lowest member id.
        assertEquals(groupA.map { it.id }, clusters[0].members.map { it.id })
        assertEquals(groupB.map { it.id }, clusters[1].members.map { it.id })
        assertEquals(groupC.map { it.id }, clusters[2].members.map { it.id })
    }

    @Test
    fun `input order does not affect cluster assignment`() {
        val members = (1L..18L).map { id ->
            entry(id = id, vector = nearVector(axis = (id % 3).toInt(), jitter = id * 0.001))
        }

        val asIs = EmbeddingClustering.cluster(members)
        val reversed = EmbeddingClustering.cluster(members.reversed())
        val shuffled = EmbeddingClustering.cluster(members.shuffled(java.util.Random(0xC0FFEE)))

        assertEquals(asIs.map { it.clusterId }.sorted(), reversed.map { it.clusterId }.sorted())
        assertEquals(asIs.map { it.clusterId }.sorted(), shuffled.map { it.clusterId }.sorted())
    }

    @Test
    fun `cluster id is content-addressable over sorted member ids`() {
        val groupA = (1L..7L).map { entry(id = it, vector = nearVector(axis = 0, jitter = it * 0.001)) }
        val groupB = (10L..16L).map { entry(id = it, vector = nearVector(axis = 1, jitter = it * 0.001)) }

        val first = EmbeddingClustering.cluster(groupA + groupB)
        val second = EmbeddingClustering.cluster(groupA + groupB)

        // Same evidence ⇒ identical ids. ADR-003 content-addressable invariant.
        assertEquals(first.map { it.clusterId }, second.map { it.clusterId })
        // Different evidence ⇒ different id.
        val withExtra = EmbeddingClustering.cluster(groupA + groupB + entry(99L, nearVector(0, 0.0001)))
        assertNotEquals(first[0].clusterId, withExtra[0].clusterId)
    }

    @Test
    fun `cluster id is a 64-char hex sha256`() {
        val members = (1L..6L).map { entry(id = it, vector = nearVector(axis = 0, jitter = it * 0.001)) }

        val clusters = EmbeddingClustering.cluster(members)

        assertEquals(1, clusters.size)
        assertTrue("cluster id must be 64 hex chars", clusters[0].clusterId.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `tight cluster of identical vectors collapses to one`() {
        val v = randomVector(seed = 1)
        val members = (1L..8L).map { entry(id = it, vector = v.copyOf()) }

        val clusters = EmbeddingClustering.cluster(members)

        assertEquals(1, clusters.size)
        assertEquals(8, clusters[0].members.size)
    }

    @Test
    fun `vectors beyond the distance cut stay in their own cluster`() {
        // Six entries: three near axis 0, three near axis 1. With a tight cut (0.05) the two
        // groups are far enough apart that they don't merge.
        val groupA = (1L..3L).map { entry(id = it, vector = nearVector(axis = 0, jitter = it * 0.001)) }
        val groupB = (4L..6L).map { entry(id = it, vector = nearVector(axis = 1, jitter = it * 0.001)) }

        val clusters = EmbeddingClustering.cluster(groupA + groupB, maxCosineDistance = 0.05)

        assertEquals(2, clusters.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `out of range distance cut throws`() {
        val members = (1L..6L).map { entry(id = it, vector = randomVector(seed = it.toInt())) }
        EmbeddingClustering.cluster(members, maxCosineDistance = 5.0)
    }

    private fun entry(id: Long, vector: FloatArray?): EntryEntity =
        EntryEntity(entryText = "entry $id", timestampEpochMs = id * 1000L).also {
            it.id = id
            it.vector = vector
        }

    private fun nearVector(axis: Int, jitter: Double): FloatArray {
        // Build a vector pointed (mostly) along one of three coordinate axes — for tests they
        // act as three perfectly-separated semantic clusters.
        val v = FloatArray(EMBED_DIM)
        v[axis] = 1.0f
        // Add a tiny perturbation so members of the same cluster aren't bit-identical.
        for (i in v.indices) v[i] = v[i] + jitter.toFloat() * ((i + 1) % 5 - 2) * 0.01f
        return v
    }

    private fun randomVector(seed: Int): FloatArray {
        val rng = java.util.Random(seed.toLong())
        return FloatArray(EMBED_DIM) { (rng.nextFloat() - 0.5f) }
    }

    private companion object {
        // Small enough for fast tests, large enough that cosine similarity is meaningful.
        const val EMBED_DIM: Int = 32
    }
}
