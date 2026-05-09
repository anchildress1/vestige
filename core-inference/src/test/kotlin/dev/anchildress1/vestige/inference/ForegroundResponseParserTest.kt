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
    fun `parses transcription and follow-up from canonical markdown`() {
        val raw = """
            ## TRANSCRIPTION
            I sat down and didn't move for an hour.

            ## FOLLOW_UP
            What were you trying to do before you sat down?
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
    fun `tolerates preface text before the first header`() {
        val raw = """
            Sure thing — here's the structured response you asked for.

            ## TRANSCRIPTION
            went to bed at four am again.

            ## FOLLOW_UP
            What pulled you toward the four-am window tonight?
        """.trimIndent()

        val success = assertInstanceOf(ForegroundResult.Success::class.java, parse(raw))
        assertEquals("went to bed at four am again.", success.transcription)
        assertEquals("What pulled you toward the four-am window tonight?", success.followUp)
    }

    @Test
    fun `tolerates extra blank lines between headers and content`() {
        val raw = "## TRANSCRIPTION\n\n\nfoo\n\n\n## FOLLOW_UP\n\n\nbar\n\n"
        val success = assertInstanceOf(ForegroundResult.Success::class.java, parse(raw))
        assertEquals("foo", success.transcription)
        assertEquals("bar", success.followUp)
    }

    @Test
    fun `treats trailing content after FOLLOW_UP as part of the follow-up body`() {
        val raw = """
            ## TRANSCRIPTION
            short note.

            ## FOLLOW_UP
            line one.
            line two.
        """.trimIndent()

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
    fun `missing transcription header returns MISSING_TRANSCRIPTION failure`() {
        val raw = """
            ## FOLLOW_UP
            i have no transcription.
        """.trimIndent()
        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, parse(raw))
        assertEquals(ForegroundResult.ParseReason.MISSING_TRANSCRIPTION, failure.reason)
        assertEquals(raw, failure.rawResponse)
    }

    @Test
    fun `missing follow-up header returns MISSING_FOLLOW_UP failure`() {
        val raw = """
            ## TRANSCRIPTION
            this is the user's voice.
        """.trimIndent()
        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, parse(raw))
        assertEquals(ForegroundResult.ParseReason.MISSING_FOLLOW_UP, failure.reason)
    }

    @Test
    fun `transcription header present but body empty returns MISSING_TRANSCRIPTION`() {
        val raw = """
            ## TRANSCRIPTION

            ## FOLLOW_UP
            you didn't say anything yet.
        """.trimIndent()
        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, parse(raw))
        assertEquals(ForegroundResult.ParseReason.MISSING_TRANSCRIPTION, failure.reason)
    }

    @Test
    fun `follow-up header present but body empty returns MISSING_FOLLOW_UP`() {
        val raw = """
            ## TRANSCRIPTION
            something the user said.

            ## FOLLOW_UP
        """.trimIndent()
        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, parse(raw))
        assertEquals(ForegroundResult.ParseReason.MISSING_FOLLOW_UP, failure.reason)
    }

    @Test
    fun `headers swapped (FOLLOW_UP before TRANSCRIPTION) returns MISSING_FOLLOW_UP`() {
        // The parser only accepts FOLLOW_UP that appears AFTER TRANSCRIPTION. A swapped order is
        // a malformed response per ADR-002 §"Output reliability" — we surface the failure rather
        // than guessing a recovery.
        val raw = """
            ## FOLLOW_UP
            first.

            ## TRANSCRIPTION
            second.
        """.trimIndent()
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
    fun `transcription text quoting the FOLLOW_UP marker is preserved verbatim`() {
        // A user dictating literal markdown ("the section called ## FOLLOW_UP, capitalized") must
        // not be sliced mid-quote — the transcription contract from `personas/shared.txt` says
        // "exact and unaltered."
        val raw = """
            ## TRANSCRIPTION
            i was reading the doc and the next section is called ## FOLLOW_UP, all caps.

            ## FOLLOW_UP
            What were you reading right before that?
        """.trimIndent()

        val success = assertInstanceOf(ForegroundResult.Success::class.java, parse(raw))
        assertEquals(
            "i was reading the doc and the next section is called ## FOLLOW_UP, all caps.",
            success.transcription,
        )
        assertEquals("What were you reading right before that?", success.followUp)
    }

    @Test
    fun `inline TRANSCRIPTION marker inside follow-up body does not break parsing`() {
        val raw = """
            ## TRANSCRIPTION
            something the user said.

            ## FOLLOW_UP
            you mentioned ## TRANSCRIPTION mid-sentence — what file were you reading?
        """.trimIndent()
        val success = assertInstanceOf(ForegroundResult.Success::class.java, parse(raw))
        assertEquals("something the user said.", success.transcription)
        assertEquals(
            "you mentioned ## TRANSCRIPTION mid-sentence — what file were you reading?",
            success.followUp,
        )
    }

    @Test
    fun `header with trailing whitespace on the line still matches`() {
        val raw = "## TRANSCRIPTION   \nfoo\n## FOLLOW_UP \t\nbar"
        val success = assertInstanceOf(ForegroundResult.Success::class.java, parse(raw))
        assertEquals("foo", success.transcription)
        assertEquals("bar", success.followUp)
    }

    @Test
    fun `inline header with content after on the same line does not match`() {
        // "## FOLLOW_UP and then more text" is not a header line — it's prose containing the
        // marker. Without a TRANSCRIPTION line, this is MISSING_TRANSCRIPTION.
        val raw = "## FOLLOW_UP and then more text on the same line\nnope"
        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, parse(raw))
        assertEquals(ForegroundResult.ParseReason.MISSING_TRANSCRIPTION, failure.reason)
    }
}
