package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.ObservationEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ObservationResponseParserTest {

    @Test
    fun `parses a clean two-observation response`() {
        val raw = """
            {
              "observations": [
                {
                  "text": "You said you'd talk to her — flagged.",
                  "evidence": "commitment-flag",
                  "fields": ["stated_commitment"]
                },
                {
                  "text": "This dump is mostly about your boss.",
                  "evidence": "theme-noticing",
                  "fields": ["tags"]
                }
              ]
            }
        """.trimIndent()

        val observations = ObservationResponseParser.parse(raw)

        assertNotNull(observations)
        assertEquals(2, observations!!.size)
        assertEquals(ObservationEvidence.COMMITMENT_FLAG, observations[0].evidence)
        assertEquals(listOf("stated_commitment"), observations[0].fields)
        assertEquals(ObservationEvidence.THEME_NOTICING, observations[1].evidence)
    }

    @Test
    fun `truncates to two when the model overshoots`() {
        val raw = """
            {
              "observations": [
                { "text": "obs one", "evidence": "theme-noticing", "fields": [] },
                { "text": "obs two", "evidence": "theme-noticing", "fields": [] },
                { "text": "obs three", "evidence": "theme-noticing", "fields": [] },
                { "text": "obs four", "evidence": "theme-noticing", "fields": [] }
              ]
            }
        """.trimIndent()

        val observations = ObservationResponseParser.parse(raw)

        assertNotNull(observations)
        assertEquals(2, observations!!.size)
        assertEquals("obs one", observations[0].text)
        assertEquals("obs two", observations[1].text)
    }

    @Test
    fun `rejects entire response when any observation hits a forbidden opening`() {
        val raw = """
            {
              "observations": [
                { "text": "You might be feeling overwhelmed.", "evidence": "theme-noticing", "fields": [] }
              ]
            }
        """.trimIndent()

        assertNull(ObservationResponseParser.parse(raw))
    }

    @Test
    fun `rejects when a forbidden phrase is embedded mid-sentence`() {
        val raw = """
            {
              "observations": [
                {
                  "text": "Three mentions of boss, and you may want to consider taking a break.",
                  "evidence": "theme-noticing",
                  "fields": ["tags"]
                }
              ]
            }
        """.trimIndent()

        assertNull(ObservationResponseParser.parse(raw))
    }

    @Test
    fun `returns null on missing observations key`() {
        val raw = """{"summary": "nothing useful"}"""
        assertNull(ObservationResponseParser.parse(raw))
    }

    @Test
    fun `returns null on empty observations array`() {
        val raw = """{"observations": []}"""
        assertNull(ObservationResponseParser.parse(raw))
    }

    @Test
    fun `returns null on unknown evidence value`() {
        val raw = """
            {
              "observations": [
                { "text": "obs", "evidence": "made-up-kind", "fields": [] }
              ]
            }
        """.trimIndent()

        assertNull(ObservationResponseParser.parse(raw))
    }

    @Test
    fun `returns null when evidence is pattern-callout because that lives in the pattern engine`() {
        val raw = """
            {
              "observations": [
                { "text": "obs", "evidence": "pattern-callout", "fields": [] }
              ]
            }
        """.trimIndent()

        assertNull(ObservationResponseParser.parse(raw))
    }

    @Test
    fun `tolerates prose wrapping the JSON block`() {
        val raw = """
            Here is your output:
            ```json
            { "observations": [ { "text": "obs", "evidence": "theme-noticing", "fields": [] } ] }
            ```
        """.trimIndent()

        val observations = ObservationResponseParser.parse(raw)

        assertNotNull(observations)
        assertEquals(1, observations!!.size)
    }

    @Test
    fun `drops observations with blank text`() {
        val raw = """{"observations": [{"text": "   ", "evidence": "theme-noticing", "fields": []}]}"""
        assertNull(ObservationResponseParser.parse(raw))
    }

    @Test
    fun `containsForbiddenPhrase scans case-insensitively`() {
        assertEquals(true, ObservationResponseParser.containsForbiddenPhrase("YOU MIGHT BE FEELING tired"))
        assertEquals(true, ObservationResponseParser.containsForbiddenPhrase("This Could Indicate a slump"))
        assertEquals(false, ObservationResponseParser.containsForbiddenPhrase("Three mentions of boss"))
    }
}
