package dev.anchildress1.vestige.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class VocabClustersCodecTest {

    @Test
    fun `encode then decode round-trips identity`() {
        val original = listOf(
            VocabCluster(
                clusterId = "a".repeat(64),
                label = "Exhaustion",
                description = "8 entries · framings: exhausted, drained, wiped",
                exampleEntryId = 42L,
                memberEntryIds = listOf(1L, 7L, 42L, 88L),
            ),
            VocabCluster(
                clusterId = "b".repeat(64),
                label = "Cognitive fog",
                description = "7 entries · framings: sluggish, foggy",
                exampleEntryId = 13L,
                memberEntryIds = listOf(13L, 91L),
            ),
        )

        val decoded = VocabClustersCodec.decode(VocabClustersCodec.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `encode of empty list returns blank string`() {
        // Persistence cost is the cooldown row — a blank column means "no clusters yet" and
        // avoids storing a useless `{"version":1,"clusters":[]}` envelope.
        assertEquals("", VocabClustersCodec.encode(emptyList()))
    }

    @Test
    fun `decode of blank string returns empty list`() {
        assertEquals(emptyList<VocabCluster>(), VocabClustersCodec.decode(""))
    }

    @Test
    fun `decode of malformed json returns empty list rather than throwing`() {
        // Defensive: a corrupted column never crashes the orchestrator's second pass.
        assertEquals(emptyList<VocabCluster>(), VocabClustersCodec.decode("not json"))
        assertEquals(emptyList<VocabCluster>(), VocabClustersCodec.decode("{\"version\":1}"))
    }

    @Test
    fun `encode escapes quotes and control chars in label and description`() {
        val cluster = VocabCluster(
            clusterId = "c".repeat(64),
            label = "Has \"quotes\"",
            description = "Line one\nLine two",
            exampleEntryId = 1L,
            memberEntryIds = listOf(1L),
        )

        val decoded = VocabClustersCodec.decode(VocabClustersCodec.encode(listOf(cluster)))

        assertEquals(listOf(cluster), decoded)
    }

    @Test
    fun `encoded JSON has stable key order id label description example member`() {
        // The column is part of the audit trail; downstream diffing depends on stable key
        // order so a re-encode of unchanged data produces identical bytes.
        val cluster = VocabCluster(
            clusterId = "d".repeat(64),
            label = "L",
            description = "D",
            exampleEntryId = 99L,
            memberEntryIds = listOf(2L, 5L),
        )

        val encoded = VocabClustersCodec.encode(listOf(cluster))

        val expected = """{"version":1,"clusters":[""" +
            """{"id":"${"d".repeat(64)}",""" +
            """"label":"L",""" +
            """"description":"D",""" +
            """"example_entry_id":99,""" +
            """"member_entry_ids":[2,5]}]}"""
        assertEquals(expected, encoded)
    }
}
