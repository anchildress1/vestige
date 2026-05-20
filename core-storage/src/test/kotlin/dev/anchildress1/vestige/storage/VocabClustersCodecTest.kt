package dev.anchildress1.vestige.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabClustersCodecTest {

    @Test
    fun `encode then decode round-trips identity`() {
        val original = listOf(
            cluster(
                label = "Exhaustion",
                description = "8 entries · framings: exhausted, drained, wiped",
                example = 42L,
                members = listOf(1L, 7L, 42L, 88L),
            ),
            cluster(
                label = "Cognitive fog",
                description = "7 entries · framings: sluggish, foggy",
                example = 13L,
                members = listOf(13L, 91L),
            ),
        )

        val decoded = VocabClustersCodec.decode(
            VocabClustersCodec.encode(original, evidenceHash = "stub"),
        )

        assertEquals(original, decoded)
    }

    @Test
    fun `encode of empty list returns blank string`() {
        assertEquals("", VocabClustersCodec.encode(emptyList(), evidenceHash = "anything"))
    }

    @Test
    fun `decode of blank string returns empty list`() {
        assertEquals(emptyList<VocabCluster>(), VocabClustersCodec.decode(""))
    }

    @Test
    fun `decode of malformed json returns empty list rather than throwing`() {
        assertEquals(emptyList<VocabCluster>(), VocabClustersCodec.decode("not json"))
        assertEquals(emptyList<VocabCluster>(), VocabClustersCodec.decode("{\"version\":1}"))
    }

    @Test
    fun `decode of clusters array with a missing required field returns empty list`() {
        // One bad item = whole envelope rejected. Documents the all-or-nothing failure mode.
        val malformed = """{"version":1,"clusters":[{"id":"a","label":"L"}]}"""
        assertEquals(emptyList<VocabCluster>(), VocabClustersCodec.decode(malformed))
    }

    @Test
    fun `encode escapes quotes backslash tab and forward-slash in label and description`() {
        val cluster = cluster(
            label = "Has \"\\quo/tes\"",
            description = "Line one\nLine\ttwo",
            example = 1L,
            members = listOf(1L),
        )

        val decoded = VocabClustersCodec.decode(
            VocabClustersCodec.encode(listOf(cluster), evidenceHash = "h"),
        )

        assertEquals(listOf(cluster), decoded)
    }

    @Test
    fun `encoded JSON has stable key order with evidence_hash up front`() {
        val cluster = VocabCluster.of(
            members = listOf(2L, 5L),
            label = "L",
            description = "D",
            exampleEntryId = 2L,
        )

        val encoded = VocabClustersCodec.encode(listOf(cluster), evidenceHash = "h")

        val expected = """{"version":1,""" +
            """"evidence_hash":"h",""" +
            """"clusters":[""" +
            """{"id":"${cluster.clusterId}",""" +
            """"label":"L",""" +
            """"description":"D",""" +
            """"example_entry_id":2,""" +
            """"member_entry_ids":[2,5]}]}"""
        assertEquals(expected, encoded)
    }

    @Test
    fun `evidenceHashIn reads the persisted hash and returns null when absent`() {
        val hash = VocabClustersCodec.evidenceHashOf(listOf(1L, 2L, 3L))
        val encoded = VocabClustersCodec.encode(
            listOf(VocabCluster.of(listOf(1L), "L", "D", 1L)),
            evidenceHash = hash,
        )

        assertEquals(hash, VocabClustersCodec.evidenceHashIn(encoded))
        assertNull(VocabClustersCodec.evidenceHashIn(""))
        // Legacy envelope without the field — null, not garbage.
        assertNull(VocabClustersCodec.evidenceHashIn("""{"version":1,"clusters":[]}"""))
    }

    @Test
    fun `evidenceHashOf is order-invariant and 64-char hex`() {
        val a = VocabClustersCodec.evidenceHashOf(listOf(3L, 1L, 2L))
        val b = VocabClustersCodec.evidenceHashOf(listOf(2L, 3L, 1L))
        assertEquals(a, b)
        assertTrue(a.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `evidenceHashOf differs for disjoint sets of the same size`() {
        assertNotNull(VocabClustersCodec.evidenceHashOf(listOf(1L, 2L, 3L)))
        assertTrue(
            VocabClustersCodec.evidenceHashOf(listOf(1L, 2L, 3L)) !=
                VocabClustersCodec.evidenceHashOf(listOf(4L, 5L, 6L)),
        )
    }

    private fun cluster(label: String, description: String, example: Long, members: List<Long>): VocabCluster =
        VocabCluster.of(
            members = members,
            label = label,
            description = description,
            exampleEntryId = example,
        )
}
