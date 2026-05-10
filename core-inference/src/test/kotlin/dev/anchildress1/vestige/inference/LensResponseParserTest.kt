package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.Lens
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LensResponseParserTest {

    @Test
    fun `parses a clean schema-conformant JSON object into LensExtraction fields`() {
        val raw = """
            {
              "tags": ["standup", "launch-doc"],
              "energy_descriptor": "flattened",
              "state_shift": true,
              "vocabulary_contradictions": [{"term_a":"fine","term_b":"flatlined","snippet":"fine but flatlined"}],
              "stated_commitment": {"text":"will draft tonight","topic_or_person":"Nora"},
              "recurrence_link": "p_aftermath_001",
              "recurrence_kind": "exact",
              "flags": []
            }
        """.trimIndent()

        val extraction = LensResponseParser.parse(Lens.LITERAL, raw)

        assertNotNull(extraction)
        assertEquals(Lens.LITERAL, extraction!!.lens)
        assertEquals(listOf("standup", "launch-doc"), extraction.fields["tags"])
        assertEquals("flattened", extraction.fields["energy_descriptor"])
        assertEquals(true, extraction.fields["state_shift"])
        assertEquals("p_aftermath_001", extraction.fields["recurrence_link"])
        assertEquals("exact", extraction.fields["recurrence_kind"])

        @Suppress("UNCHECKED_CAST")
        val commitment = extraction.fields["stated_commitment"] as Map<String, Any?>
        assertEquals("will draft tonight", commitment["text"])
        assertEquals("Nora", commitment["topic_or_person"])

        @Suppress("UNCHECKED_CAST")
        val contradictions = extraction.fields["vocabulary_contradictions"] as List<Map<String, Any?>>
        assertEquals(1, contradictions.size)
        assertEquals("fine", contradictions[0]["term_a"])

        assertTrue(extraction.flags.isEmpty())
    }

    @Test
    fun `routes Skeptical flags off the fields map and onto the flags list`() {
        // `flags` are emitted by the model as `{kind, snippet, note}` objects per
        // `core-inference/src/main/resources/lenses/output-schema.txt`. The parser encodes each
        // object into the stable `kind:snippet:note` form so convergence-time equality is
        // deterministic regardless of JSON key order or sub-key spacing.
        val raw = """
            {
              "tags": ["audit"],
              "energy_descriptor": null,
              "state_shift": false,
              "vocabulary_contradictions": [],
              "stated_commitment": null,
              "recurrence_link": null,
              "recurrence_kind": null,
              "flags": [
                {"kind":"state-behavior-mismatch","snippet":"fine but flatlined","note":"stated state contradicts described behavior"}
              ]
            }
        """.trimIndent()

        val extraction = LensResponseParser.parse(Lens.SKEPTICAL, raw)

        assertNotNull(extraction)
        assertEquals(
            listOf("state-behavior-mismatch:fine but flatlined:stated state contradicts described behavior"),
            extraction!!.flags,
        )
        assertNull(extraction.fields["energy_descriptor"])
        assertNull(extraction.fields["stated_commitment"])
    }

    @Test
    fun `flag with missing sub-keys collapses to empty segments and keeps the colon count fixed`() {
        val raw = """
            {"flags":[{"kind":"unsupported-recurrence","snippet":"third Tuesday in a row"}]}
        """.trimIndent()

        val extraction = LensResponseParser.parse(Lens.SKEPTICAL, raw)

        assertNotNull(extraction)
        assertEquals(listOf("unsupported-recurrence:third Tuesday in a row:"), extraction!!.flags)
    }

    @Test
    fun `flag with all sub-keys missing or empty is dropped`() {
        val raw = """{"flags":[{},{"kind":""}]}"""

        val extraction = LensResponseParser.parse(Lens.SKEPTICAL, raw)

        assertNotNull(extraction)
        assertTrue(extraction!!.flags.isEmpty())
    }

    @Test
    fun `legacy bare-string flag entries pass through unchanged`() {
        val raw = """{"flags":["energy_descriptor:contradicts:fine"]}"""

        val extraction = LensResponseParser.parse(Lens.SKEPTICAL, raw)

        assertNotNull(extraction)
        assertEquals(listOf("energy_descriptor:contradicts:fine"), extraction!!.flags)
    }

    @Test
    fun `tolerates surrounding prose and markdown fences by extracting the first balanced object`() {
        val raw = """
            Here's the JSON:

            ```json
            {"tags":["a"],"energy_descriptor":"calm","state_shift":false,"vocabulary_contradictions":[],"stated_commitment":null,"recurrence_link":null,"recurrence_kind":null,"flags":[]}
            ```

            Done.
        """.trimIndent()

        val extraction = LensResponseParser.parse(Lens.INFERENTIAL, raw)

        assertNotNull(extraction)
        assertEquals(listOf("a"), extraction!!.fields["tags"])
        assertEquals("calm", extraction.fields["energy_descriptor"])
    }

    @Test
    fun `does not split on a brace inside a string literal`() {
        val raw = """{"tags":["{}"],"energy_descriptor":"a {b} c","state_shift":false,""" +
            """"vocabulary_contradictions":[],"stated_commitment":null,"recurrence_link":null,""" +
            """"recurrence_kind":null,"flags":[]}"""

        val extraction = LensResponseParser.parse(Lens.LITERAL, raw)

        assertNotNull(extraction)
        assertEquals(listOf("{}"), extraction!!.fields["tags"])
        assertEquals("a {b} c", extraction.fields["energy_descriptor"])
    }

    @Test
    fun `returns null on blank input`() {
        assertNull(LensResponseParser.parse(Lens.LITERAL, ""))
        assertNull(LensResponseParser.parse(Lens.LITERAL, "   \n\t  "))
    }

    @Test
    fun `returns null when no JSON object is present`() {
        assertNull(LensResponseParser.parse(Lens.LITERAL, "the model forgot the schema again"))
    }

    @Test
    fun `returns null when the JSON object is truncated mid-stream`() {
        val raw = """{"tags":["a","b"],"energy_descriptor":"calm"""
        assertNull(LensResponseParser.parse(Lens.LITERAL, raw))
    }

    @Test
    fun `returns null when the payload is a JSON array, not an object`() {
        // Schema requires an object; an array at the top level is a parse failure (the worker
        // treats this lens as "no opinion" per ADR-002 §"Convergence edge cases").
        assertNull(LensResponseParser.parse(Lens.LITERAL, """["tags","not","an","object"]"""))
    }

    @Test
    fun `normalizes tags by trimming and lowercasing so cross-lens equality works`() {
        val raw = """{"tags":["  Standup  ", "Launch-Doc", "", "  "]}"""
        val extraction = LensResponseParser.parse(Lens.LITERAL, raw)

        assertNotNull(extraction)
        // Mixed-case + padded tags converge to the same form across lenses; empty tags drop.
        assertEquals(listOf("standup", "launch-doc"), extraction!!.fields["tags"])
    }

    @Test
    fun `drops flags from non-Skeptical lens output`() {
        // Literal/Inferential drift into emitting flags would corrupt convergence; the schema is
        // explicit that flags belong only to the Skeptical lens.
        val raw = """{"flags":[{"kind":"state-behavior-mismatch","snippet":"x","note":"y"}]}"""

        val literal = LensResponseParser.parse(Lens.LITERAL, raw)
        val inferential = LensResponseParser.parse(Lens.INFERENTIAL, raw)
        val skeptical = LensResponseParser.parse(Lens.SKEPTICAL, raw)

        assertNotNull(literal)
        assertNotNull(inferential)
        assertNotNull(skeptical)
        assertTrue(literal!!.flags.isEmpty())
        assertTrue(inferential!!.flags.isEmpty())
        assertEquals(listOf("state-behavior-mismatch:x:y"), skeptical!!.flags)
    }

    @Test
    fun `keeps scanning when an earlier brace block is not parseable JSON`() {
        // A model that echoes schema commentary like `{kind, snippet, note}` ahead of the actual
        // payload would have made a single-shot parser return null and burn a retry. The parser
        // walks past the unparseable block and finds the real object.
        val raw = """
            We expect each flag as {kind, snippet, note}. Here it is:
            {"tags":["a"],"flags":[]}
        """.trimIndent()

        val extraction = LensResponseParser.parse(Lens.LITERAL, raw)

        assertNotNull(extraction)
        assertEquals(listOf("a"), extraction!!.fields["tags"])
    }

    @Test
    fun `rejects array-wrapped payload like square-bracket inner object`() {
        // The schema requires a top-level object; `[{...}]` violates that. The parser refuses to
        // unpack the inner object so a malformed shape can't masquerade as valid extraction data.
        val raw = """[{"tags":["a"]}]"""
        assertNull(LensResponseParser.parse(Lens.LITERAL, raw))
    }

    @Test
    fun `accepts a brace after an unrelated bracket pair earlier in the prose`() {
        // `[note]` is prose, not an array opener for the payload. The "is the payload wrapped"
        // check looks at whether nothing-but-whitespace separates the bracket from the brace.
        val raw = """[note] {"tags":["a"]}"""
        val extraction = LensResponseParser.parse(Lens.LITERAL, raw)

        assertNotNull(extraction)
        assertEquals(listOf("a"), extraction!!.fields["tags"])
    }

    @Test
    fun `treats JSON null and missing keys as equivalent absence`() {
        val raw = """{"tags":[],"energy_descriptor":null}"""
        val extraction = LensResponseParser.parse(Lens.LITERAL, raw)

        assertNotNull(extraction)
        assertNull(extraction!!.fields["energy_descriptor"])
        // Missing keys come through as null (the convergence resolver treats null and absent the
        // same way, so the parser flattens both to null).
        assertNull(extraction.fields["stated_commitment"])
        assertNull(extraction.fields["recurrence_link"])
    }
}
