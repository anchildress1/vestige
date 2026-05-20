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
    fun `blank and non-string flag entries are dropped, valid ones kept`() {
        // encodeFlag arms: blank String -> null, non-String/Map (number, bool) -> null,
        // non-blank String -> kept.
        val raw = """{"flags":["keep-me","   ",42,true]}"""

        val extraction = LensResponseParser.parse(Lens.SKEPTICAL, raw)

        assertNotNull(extraction)
        assertEquals(listOf("keep-me"), extraction!!.flags)
    }

    @Test
    fun `tags drop whitespace-only and non-string entries, lowercasing the rest`() {
        // normalizeTag arms: valid String -> trimmed+lowercased, "   " -> null, non-String -> null.
        val raw = """{"tags":["Standup","   ",7]}"""

        val extraction = LensResponseParser.parse(Lens.LITERAL, raw)

        assertNotNull(extraction)
        assertEquals(listOf("standup"), extraction!!.fields["tags"])
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
    fun `repairs missing commas between sibling object fields in skeptical payload`() {
        val raw = """
            {
            "tags": ["sink", "noon", "1pm", "three-hours-later"],
            "energy_descriptor": null,
            "state_shift": true
            "vocabulary_contradictions": [
            {
            "term_a": "fine",
            "term_b": "not tired exactly",
            "snippet": "completely fine by 1pm i was gone not tired exactly"
            }
            ]
            "stated_commitment": null
            "recurrence_link": null
            "recurrence_kind": null
            "flags": [
            {
            "kind": "vocabulary-contradiction",
            "snippet": "completely fine by 1pm i was gone not tired exactly",
            "note": "The user describes a state of being fine then immediately negates it with 'not tired exactly'."
            }
            ]
            }
        """.trimIndent()

        val extraction = LensResponseParser.parse(Lens.SKEPTICAL, raw)

        assertNotNull(extraction)
        assertEquals(listOf("sink", "noon", "1pm", "three-hours-later"), extraction!!.fields["tags"])
        assertEquals(true, extraction.fields["state_shift"])
        assertEquals(
            listOf(
                "vocabulary-contradiction:completely fine by 1pm i was gone not tired exactly:" +
                    "The user describes a state of being fine then immediately negates it with 'not tired exactly'.",
            ),
            extraction.flags,
        )
    }

    @Test
    fun `drops dangling quoted array item so the rest of the lens payload can parse`() {
        val raw = """
            {
            "tags": [
            "work",
            "
            ],
            "energy_descriptor": null,
            "state_shift": false,
            "vocabulary_contradictions": [],
            "stated_commitment": null,
            "recurrence_link": null,
            "recurrence_kind": null,
            "flags": []
            }
        """.trimIndent()

        val extraction = LensResponseParser.parse(Lens.SKEPTICAL, raw)

        assertNotNull(extraction)
        assertEquals(listOf("work"), extraction!!.fields["tags"])
        assertEquals(false, extraction.fields["state_shift"])
        assertTrue(extraction.flags.isEmpty())
    }

    @Test
    fun `returns null when no schema-shaped object is present`() {
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
    fun `accepts array-wrapped payload like square-bracket inner object`() {
        val raw = """[{"tags":["a"]}]"""
        val extraction = LensResponseParser.parse(Lens.LITERAL, raw)

        assertNotNull(extraction)
        assertEquals(listOf("a"), extraction!!.fields["tags"])
    }

    @Test
    fun `accepts a brace after an unrelated bracket pair earlier in the prose`() {
        // `[note]` is prose, not an array opener for the payload. The "is the payload wrapped"
        // check looks at the immediate predecessor of the first `{`, not the first `[` anywhere
        // in the response.
        val raw = """[note] {"tags":["a"]}"""
        val extraction = LensResponseParser.parse(Lens.LITERAL, raw)

        assertNotNull(extraction)
        assertEquals(listOf("a"), extraction!!.fields["tags"])
    }

    @Test
    fun `accepts array-wrapped payload when bracketed prose appears first`() {
        val raw = """[note] [{"tags":["a"]}]"""
        val extraction = LensResponseParser.parse(Lens.LITERAL, raw)

        assertNotNull(extraction)
        assertEquals(listOf("a"), extraction!!.fields["tags"])
    }

    @Test
    fun `accepts array-wrapped payload after earlier non-JSON brace commentary`() {
        val raw = """
            We expect flags shaped like {kind, snippet, note}.
            [{"tags":["a"]}]
        """.trimIndent()

        val extraction = LensResponseParser.parse(Lens.LITERAL, raw)

        assertNotNull(extraction)
        assertEquals(listOf("a"), extraction!!.fields["tags"])
    }

    @Test
    fun `skips parseable prose object that does not contain schema keys`() {
        val raw = """
            Flag example: {"kind":"vocabulary-contradiction","snippet":"x","note":"y"}
            Actual payload: {"tags":["work"],"flags":[]}
        """.trimIndent()

        val extraction = LensResponseParser.parse(Lens.LITERAL, raw)

        assertNotNull(extraction)
        assertEquals(listOf("work"), extraction!!.fields["tags"])
    }

    @Test
    fun `unwraps schema object from a model-added result envelope`() {
        val raw = """
            {
              "analysis": "ignored",
              "result": {
                "tags": ["Work"],
                "energy_descriptor": "flat",
                "state_shift": true,
                "flags": []
              }
            }
        """.trimIndent()

        val extraction = LensResponseParser.parse(Lens.INFERENTIAL, raw)

        assertNotNull(extraction)
        assertEquals(listOf("work"), extraction!!.fields["tags"])
        assertEquals("flat", extraction.fields["energy_descriptor"])
        assertEquals(true, extraction.fields["state_shift"])
    }

    @Test
    fun `repairs trailing commas in object and array payloads`() {
        val raw = """
            {
              "tags": ["work",],
              "energy_descriptor": "flat",
              "state_shift": false,
              "flags": [],
            }
        """.trimIndent()

        val extraction = LensResponseParser.parse(Lens.LITERAL, raw)

        assertNotNull(extraction)
        assertEquals(listOf("work"), extraction!!.fields["tags"])
        assertEquals(false, extraction.fields["state_shift"])
    }

    @Test
    fun `repairs duplicate commas between sibling fields after trimming payload whitespace`() {
        val raw = """

            
              {
                "tags": ["standup", "payroll-doc"],
                "energy_descriptor": "flat",
                "state_shift": true,
                ,"vocabulary_contradictions": [
                  {
                    "term_a": "fine",
                    "term_b": "stuck",
                    "snippet": "I said I was fine but felt stuck"
                  }
                ],
                "stated_commitment": null,
                "recurrence_link": null,
                "recurrence_kind": null,
                "flags": []
              }
            

        """.trimIndent()

        val extraction = LensResponseParser.parse(Lens.INFERENTIAL, raw)

        assertNotNull(extraction)
        assertEquals(listOf("standup", "payroll-doc"), extraction!!.fields["tags"])
        assertEquals("flat", extraction.fields["energy_descriptor"])
        assertEquals(true, extraction.fields["state_shift"])
        @Suppress("UNCHECKED_CAST")
        val contradictions = extraction.fields["vocabulary_contradictions"] as List<Map<String, Any?>>
        assertEquals("fine", contradictions.single()["term_a"])
    }

    @Test
    fun `repairs duplicate commas immediately after object open`() {
        val raw = """
            {
              ,
              "tags": ["work"],
              "energy_descriptor": "flat",
              "state_shift": false,
              "vocabulary_contradictions": [],
              "stated_commitment": null,
              "recurrence_link": null,
              "recurrence_kind": null,
              "flags": []
            }
        """.trimIndent()

        val extraction = LensResponseParser.parse(Lens.LITERAL, raw)

        assertNotNull(extraction)
        assertEquals(listOf("work"), extraction!!.fields["tags"])
        assertEquals("flat", extraction.fields["energy_descriptor"])
    }

    @Test
    fun `repairs unescaped quotes and missing closing quote in skeptical note string`() {
        val raw = """
            {
              "tags": ["standup", "kitchen", "payroll-doc", "stuck", "muttering"],
              "energy_descriptor": "flat",
              "state_shift": true,
              "vocabulary_contradictions": [
                {
                  "term_a": "fine",
                  "term_b": "stuck",
                  "snippet": "I said I was fine but felt stuck"
                }
              ],
              "stated_commitment": null,
              "recurrence_link": null,
              "recurrence_kind": null,
              "flags": [
                {
                  "kind": "vocabulary-contradiction",
                  "snippet": "I said I was fine but felt stuck",
                  "note": ""fine" and "stuck" are used to describe the same feeling state.
            }]}
        """.trimIndent()

        val extraction = LensResponseParser.parse(Lens.SKEPTICAL, raw)

        assertNotNull(extraction)
        assertEquals(listOf("standup", "kitchen", "payroll-doc", "stuck", "muttering"), extraction!!.fields["tags"])
        assertEquals(
            listOf(
                "vocabulary-contradiction:I said I was fine but felt stuck:" +
                    "\"fine\" and \"stuck\" are used to describe the same feeling state.",
            ),
            extraction.flags,
        )
    }

    @Test
    fun `repairs unquoted schema keys`() {
        val raw = """
            {
              tags: ["Work"],
              energy_descriptor: "flat",
              state_shift: false,
              flags: []
            }
        """.trimIndent()

        val extraction = LensResponseParser.parse(Lens.LITERAL, raw)

        assertNotNull(extraction)
        assertEquals(listOf("work"), extraction!!.fields["tags"])
        assertEquals("flat", extraction.fields["energy_descriptor"])
    }

    @Test
    fun `repairs curly double quotes around schema payload`() {
        val raw = """
            {
              “tags”: [“Work”],
              “energy_descriptor”: “flat”,
              “state_shift”: false,
              “flags”: []
            }
        """.trimIndent()

        val extraction = LensResponseParser.parse(Lens.LITERAL, raw)

        assertNotNull(extraction)
        assertEquals(listOf("work"), extraction!!.fields["tags"])
        assertEquals("flat", extraction.fields["energy_descriptor"])
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
