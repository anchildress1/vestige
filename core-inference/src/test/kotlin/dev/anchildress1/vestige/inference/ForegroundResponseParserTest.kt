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
    fun `wrapping whitespace inside tag bodies is auto-normalized`() {
        // The model's pretty-print (`<tag>\n...\n</tag>`) is formatting, not content —
        // auto-normalize so downstream `entry_text` doesn't carry stray newlines into the
        // markdown store. The user's spoken words are what's between the boundary whitespace.
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
        assertEquals("this is the user's voice.", failure.recoveredTranscription)
    }

    @Test
    fun `transcription tag present but body empty returns MISSING_TRANSCRIPTION`() {
        val raw = "<transcription></transcription>\n<follow_up>you didn't say anything yet.</follow_up>"
        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, parse(raw))
        assertEquals(ForegroundResult.ParseReason.MISSING_TRANSCRIPTION, failure.reason)
    }

    @Test
    fun `empty transcription beats missing follow_up in failure precedence`() {
        // `<transcription></transcription>` with no follow_up tag is fundamentally a
        // missing-transcription failure — that's the user's words not landing, the more
        // important signal for STT-C telemetry and caller recovery. Before codex round 6
        // the parser reported MISSING_FOLLOW_UP first because the missing-tag check ran
        // before the empty-content check.
        val raw = "<transcription></transcription>"
        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, parse(raw))
        assertEquals(ForegroundResult.ParseReason.MISSING_TRANSCRIPTION, failure.reason)
    }

    @Test
    fun `whitespace-only transcription with missing follow_up returns MISSING_TRANSCRIPTION`() {
        val raw = "<transcription>   \n\t\n</transcription>"
        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, parse(raw))
        assertEquals(ForegroundResult.ParseReason.MISSING_TRANSCRIPTION, failure.reason)
    }

    @Test
    fun `transcription tag with whitespace-only body returns MISSING_TRANSCRIPTION`() {
        // Auto-normalization trims wrapping whitespace, so a body of pure whitespace becomes
        // empty after trim — same MISSING_TRANSCRIPTION outcome as a literally empty body.
        val raw = "<transcription>   \n\t\n</transcription>\n<follow_up>nothing was said?</follow_up>"
        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, parse(raw))
        assertEquals(ForegroundResult.ParseReason.MISSING_TRANSCRIPTION, failure.reason)
    }

    @Test
    fun `follow-up tag present but body empty returns MISSING_FOLLOW_UP`() {
        val raw = "<transcription>something the user said.</transcription>\n<follow_up></follow_up>"
        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, parse(raw))
        assertEquals(ForegroundResult.ParseReason.MISSING_FOLLOW_UP, failure.reason)
        assertEquals("something the user said.", failure.recoveredTranscription)
    }

    @Test
    fun `follow-up tag with whitespace-only body returns MISSING_FOLLOW_UP with recovered transcription`() {
        // Symmetric to the whitespace-only transcription case: trim collapses the body to empty,
        // so the user's words are preserved but the model's response is absent.
        val raw = "<transcription>the user spoke clearly.</transcription>\n<follow_up>   \n\t  </follow_up>"
        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, parse(raw))
        assertEquals(ForegroundResult.ParseReason.MISSING_FOLLOW_UP, failure.reason)
        assertEquals("the user spoke clearly.", failure.recoveredTranscription)
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
        val transcriptionContent = "i was reading the doc and the next section is called\n" +
            "## FOLLOW_UP\n" +
            "all in caps."
        val raw = "<transcription>$transcriptionContent</transcription>\n" +
            "<follow_up>What were you reading right before that?</follow_up>"

        val success = assertInstanceOf(ForegroundResult.Success::class.java, parse(raw))
        assertEquals(transcriptionContent, success.transcription)
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
    fun `multiple transcription blocks return AMBIGUOUS_BLOCKS — never guess`() {
        // Codex review rounds 3 and 4: first-pair picks an echoed example; last-pair picks a
        // trailing reminder. Either heuristic silently corrupts the saved entry. The contract
        // is "exactly one of each" — anything else is a typed parse failure surfaced for STT-C
        // instrumentation and caller-side recovery (re-prompt, error UI, etc.).
        val raw = "<transcription>first</transcription><follow_up>q1</follow_up>\n" +
            "<transcription>second</transcription><follow_up>q2</follow_up>"
        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, parse(raw))
        assertEquals(ForegroundResult.ParseReason.AMBIGUOUS_BLOCKS, failure.reason)
        // singleOrNull() returns null when count > 1, so there is no recoverable transcription.
        assertEquals(null, failure.recoveredTranscription)
    }

    @Test
    fun `duplicate transcription tag alone returns AMBIGUOUS_BLOCKS`() {
        val raw = "<transcription>one</transcription>\n" +
            "<transcription>two</transcription>\n<follow_up>q</follow_up>"
        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, parse(raw))
        assertEquals(ForegroundResult.ParseReason.AMBIGUOUS_BLOCKS, failure.reason)
        assertEquals(null, failure.recoveredTranscription)
    }

    @Test
    fun `duplicate follow-up tag alone returns AMBIGUOUS_BLOCKS`() {
        val raw = "<transcription>just one transcription.</transcription>\n" +
            "<follow_up>first follow-up.</follow_up>\n" +
            "<follow_up>second follow-up.</follow_up>"
        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, parse(raw))
        assertEquals(ForegroundResult.ParseReason.AMBIGUOUS_BLOCKS, failure.reason)
        assertEquals("just one transcription.", failure.recoveredTranscription)
    }
}
