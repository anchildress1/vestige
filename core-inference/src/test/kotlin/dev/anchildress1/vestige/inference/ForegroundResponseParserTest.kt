package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.Persona
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.time.Instant

class ForegroundResponseParserTest {

    private val persona = Persona.WITNESS
    private val completedAt: Instant = Instant.parse("2026-05-09T12:00:00Z")
    private val elapsedMs = 1_337L

    private fun parse(raw: String) = ForegroundResponseParser.parse(raw, persona, elapsedMs, completedAt)

    @Test
    fun `parses transcription and follow-up from canonical XML tags`() {
        val raw = """
            <transcription>I sat down and didn't move for an hour.</transcription>
            <follow_up>What were you trying to do before you sat down?</follow_up>
        """.trimIndent()

        val result = parse(raw)

        val success = assertInstanceOf(ForegroundResult.Success::class.java, result)
        assertEquals("I sat down and didn't move for an hour.", success.transcription)
        assertEquals("What were you trying to do before you sat down?", success.followUp)
        assertEquals(persona, success.persona)
        assertEquals(elapsedMs, success.elapsedMs)
        assertEquals(completedAt, success.completedAt)
        assertSame(raw, success.rawResponse)
    }

    @Test
    fun `tolerates preface text before the first tag`() {
        val raw = """
            Sure thing — here's the structured response you asked for.

            <transcription>went to bed at four am again.</transcription>
            <follow_up>What pulled you toward the four-am window tonight?</follow_up>
        """.trimIndent()

        val success = assertInstanceOf(ForegroundResult.Success::class.java, parse(raw))
        assertEquals("went to bed at four am again.", success.transcription)
        assertEquals("What pulled you toward the four-am window tonight?", success.followUp)
    }

    @Test
    fun `tolerates whitespace and newlines between tags`() {
        val raw = "<transcription>\n\nfoo\n\n</transcription>\n\n\n<follow_up>\n\nbar\n\n</follow_up>\n\n"
        val success = assertInstanceOf(ForegroundResult.Success::class.java, parse(raw))
        assertEquals("foo", success.transcription)
        assertEquals("bar", success.followUp)
    }

    @Test
    fun `multi-line follow-up body is preserved`() {
        val raw = "<transcription>short note.</transcription>\n<follow_up>line one.\nline two.</follow_up>"
        val success = assertInstanceOf(ForegroundResult.Success::class.java, parse(raw))
        assertEquals("line one.\nline two.", success.followUp)
    }

    @Test
    fun `empty raw response returns EMPTY_RESPONSE failure`() {
        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, parse(""))
        assertEquals(ForegroundResult.ParseReason.EMPTY_RESPONSE, failure.reason)
        assertEquals(persona, failure.persona)
        assertEquals(elapsedMs, failure.elapsedMs)
    }

    @Test
    fun `whitespace-only response returns EMPTY_RESPONSE failure`() {
        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, parse("   \n\t  "))
        assertEquals(ForegroundResult.ParseReason.EMPTY_RESPONSE, failure.reason)
    }

    @Test
    fun `missing transcription tag returns MISSING_TRANSCRIPTION failure`() {
        val raw = "<follow_up>i have no transcription.</follow_up>"
        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, parse(raw))
        assertEquals(ForegroundResult.ParseReason.MISSING_TRANSCRIPTION, failure.reason)
        assertEquals(raw, failure.rawResponse)
    }

    @Test
    fun `missing follow-up tag returns MISSING_FOLLOW_UP failure`() {
        val raw = "<transcription>this is the user's voice.</transcription>"
        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, parse(raw))
        assertEquals(ForegroundResult.ParseReason.MISSING_FOLLOW_UP, failure.reason)
    }

    @Test
    fun `transcription tag present but body empty returns MISSING_TRANSCRIPTION`() {
        val raw = "<transcription></transcription>\n<follow_up>you didn't say anything yet.</follow_up>"
        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, parse(raw))
        assertEquals(ForegroundResult.ParseReason.MISSING_TRANSCRIPTION, failure.reason)
    }

    @Test
    fun `follow-up tag present but body empty returns MISSING_FOLLOW_UP`() {
        val raw = "<transcription>something the user said.</transcription>\n<follow_up></follow_up>"
        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, parse(raw))
        assertEquals(ForegroundResult.ParseReason.MISSING_FOLLOW_UP, failure.reason)
    }

    @Test
    fun `tags swapped (follow_up before transcription) returns MISSING_FOLLOW_UP`() {
        // The parser only accepts <follow_up> that appears AFTER <transcription>. A swapped order
        // is a malformed response per ADR-002 §"Output reliability" — we surface the failure
        // rather than guessing a recovery.
        val raw = "<follow_up>first.</follow_up>\n<transcription>second.</transcription>"
        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, parse(raw))
        assertEquals(ForegroundResult.ParseReason.MISSING_FOLLOW_UP, failure.reason)
    }

    @Test
    fun `failure preserves persona, raw, elapsed, and completedAt`() {
        val raw = "garbage with no headers"
        val result = ForegroundResponseParser.parse(raw, Persona.HARDASS, 9_999L, completedAt)
        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, result)
        assertEquals(Persona.HARDASS, failure.persona)
        assertEquals(raw, failure.rawResponse)
        assertEquals(9_999L, failure.elapsedMs)
        assertEquals(completedAt, failure.completedAt)
    }

    @Test
    fun `transcription text mentioning markdown headers is preserved verbatim`() {
        // A user dictating "## FOLLOW_UP" inline (or even on its own line) must not split the
        // transcription — the verbatim contract from `personas/shared.txt` says "exact and
        // unaltered." XML tags bound the section, so markdown markers are just content.
        val raw = "<transcription>i was reading the doc and the next section is called\n" +
            "## FOLLOW_UP\n" +
            "all in caps.</transcription>\n" +
            "<follow_up>What were you reading right before that?</follow_up>"

        val success = assertInstanceOf(ForegroundResult.Success::class.java, parse(raw))
        assertEquals(
            "i was reading the doc and the next section is called\n## FOLLOW_UP\nall in caps.",
            success.transcription,
        )
        assertEquals("What were you reading right before that?", success.followUp)
    }

    @Test
    fun `transcription text containing markdown TRANSCRIPTION marker is preserved verbatim`() {
        val raw = """
            <transcription>and then a line that says ## TRANSCRIPTION on its own.</transcription>
            <follow_up>where were you reading?</follow_up>
        """.trimIndent()
        val success = assertInstanceOf(ForegroundResult.Success::class.java, parse(raw))
        assertEquals("and then a line that says ## TRANSCRIPTION on its own.", success.transcription)
        assertEquals("where were you reading?", success.followUp)
    }

    @Test
    fun `unbalanced transcription tag (no closing) returns MISSING_TRANSCRIPTION`() {
        val raw = "<transcription>I started speaking but the close tag is missing.\n<follow_up>nope</follow_up>"
        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, parse(raw))
        assertEquals(ForegroundResult.ParseReason.MISSING_TRANSCRIPTION, failure.reason)
    }

    @Test
    fun `multiple transcription blocks pick the first balanced pair`() {
        val raw = "<transcription>first</transcription><follow_up>q1</follow_up>" +
            "<transcription>second</transcription><follow_up>q2</follow_up>"
        val success = assertInstanceOf(ForegroundResult.Success::class.java, parse(raw))
        assertEquals("first", success.transcription)
        assertEquals("q1", success.followUp)
    }
}
