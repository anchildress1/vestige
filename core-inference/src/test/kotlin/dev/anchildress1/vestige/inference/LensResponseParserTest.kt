package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.Lens
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LensResponseParserTest {

    @Test
    fun `parses flat key-value lines into LensExtraction fields`() {
        val raw = """
            template_label: Aftermath
            tags: Standup, battery-died
            vocabulary: Drained
            commitment: send the doc to nora
            commitment_topic: Nora
        """.trimIndent()

        val extraction = LensResponseParser.parse(Lens.LITERAL, raw)

        assertNotNull(extraction)
        assertEquals(Lens.LITERAL, extraction!!.lens)
        assertEquals(listOf("standup", "battery-died"), extraction.fields["tags"])
        assertEquals("aftermath", extraction.fields["template_label"])
        assertEquals("drained", extraction.fields["vocabulary"])
        assertEquals(
            mapOf("text" to "send the doc to nora", "topic_or_person" to "Nora"),
            extraction.fields["stated_commitment"],
        )
        assertTrue(extraction.flags.isEmpty())
    }

    @Test
    fun `folds a nullish vocabulary back to null`() {
        val extraction = LensResponseParser.parse(Lens.INFERENTIAL, "template_label: audit\nvocabulary: none")
        assertNotNull(extraction)
        assertNull(extraction!!.fields["vocabulary"])
    }

    @Test
    fun `skeptical flag lines collapse to kind colon snippet colon note`() {
        val raw = """
            template_label: aftermath
            flag: commitment-without-anchor | send the doc | no deadline named
            flag: unsupported-recurrence | again | no history
        """.trimIndent()

        val extraction = LensResponseParser.parse(Lens.SKEPTICAL, raw)

        assertNotNull(extraction)
        assertEquals(
            listOf(
                "commitment-without-anchor:send the doc:no deadline named",
                "unsupported-recurrence:again:no history",
            ),
            extraction!!.flags,
        )
    }

    @Test
    fun `non-skeptical lenses ignore flag lines`() {
        val extraction = LensResponseParser.parse(
            Lens.LITERAL,
            "template_label: aftermath\nflag: commitment-without-anchor | x | y",
        )
        assertNotNull(extraction)
        assertTrue(extraction!!.flags.isEmpty())
    }

    @Test
    fun `flag with missing trailing parts keeps empty segments`() {
        val extraction = LensResponseParser.parse(Lens.SKEPTICAL, "flag: time-inconsistency")
        assertEquals(listOf("time-inconsistency::"), extraction!!.flags)
    }

    @Test
    fun `tags drop blank and nullish entries and lowercase the rest`() {
        val extraction = LensResponseParser.parse(Lens.LITERAL, "tags: Standup, , none,  Meeting ")
        assertEquals(listOf("standup", "meeting"), extraction!!.fields["tags"])
    }

    @Test
    fun `tolerates surrounding prose, fences, and unknown lines`() {
        val raw = """
            Here is my read:
            ```
            template_label: stalled
            random_key: ignored
            tags: doc, wall
            ```
            done.
        """.trimIndent()

        val extraction = LensResponseParser.parse(Lens.INFERENTIAL, raw)

        assertNotNull(extraction)
        assertEquals("stalled", extraction!!.fields["template_label"])
        assertEquals(listOf("doc", "wall"), extraction.fields["tags"])
    }

    @Test
    fun `omitted commitment leaves stated_commitment null`() {
        val extraction = LensResponseParser.parse(Lens.LITERAL, "template_label: audit\ntags: doc")
        assertNotNull(extraction)
        assertNull(extraction!!.fields["stated_commitment"])
    }

    @Test
    fun `returns null on blank input`() {
        assertNull(LensResponseParser.parse(Lens.LITERAL, ""))
        assertNull(LensResponseParser.parse(Lens.LITERAL, "   \n\t  "))
    }

    @Test
    fun `returns null when no recognizable field line is present`() {
        assertNull(LensResponseParser.parse(Lens.LITERAL, "the model forgot the format again"))
    }
}
