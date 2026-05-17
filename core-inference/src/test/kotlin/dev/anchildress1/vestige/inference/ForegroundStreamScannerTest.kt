package dev.anchildress1.vestige.inference

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ForegroundStreamScannerTest {

    private fun ForegroundStreamScanner.feed(vararg chunks: String): List<ForegroundStreamEvent> =
        chunks.flatMap { accept(it) }

    @Test
    fun `transcription surfaces once when both tags land in one chunk`() {
        val scanner = ForegroundStreamScanner()

        val events = scanner.feed("<transcription>i kept reopening it</transcription>")

        val transcriptions = events.filterIsInstance<ForegroundStreamEvent.Transcription>()
        assertEquals(1, transcriptions.size)
        assertEquals("i kept reopening it", transcriptions.single().text)
    }

    @Test
    fun `transcription resolves when the close tag is split across chunks`() {
        val scanner = ForegroundStreamScanner()

        val first = scanner.accept("<transcription>verbatim words</transcr")
        val second = scanner.accept("iption>")

        assertTrue(
            first.none { it is ForegroundStreamEvent.Transcription },
            "no transcription until the close tag completes",
        )
        val t = second.filterIsInstance<ForegroundStreamEvent.Transcription>().single()
        assertEquals("verbatim words", t.text)
    }

    @Test
    fun `transcription is emitted at most once across many chunks`() {
        val scanner = ForegroundStreamScanner()

        val events = scanner.feed(
            "<transcription>done</transcription>",
            "<follow_up>q</follow_up>",
            "trailing model noise",
        )

        assertEquals(1, events.count { it is ForegroundStreamEvent.Transcription })
    }

    @Test
    fun `an empty transcription body emits no Transcription event`() {
        val scanner = ForegroundStreamScanner()

        val events = scanner.feed("<transcription>   </transcription><follow_up>still asks</follow_up>")

        assertTrue(events.none { it is ForegroundStreamEvent.Transcription })
        // The raw is still accumulated so the terminal parser owns the failure verdict.
        assertTrue(scanner.accumulated.contains("<transcription>"))
    }

    @Test
    fun `follow-up arrives as incremental deltas that reconstruct the body`() {
        val scanner = ForegroundStreamScanner()

        val events = scanner.feed(
            "<transcription>t</transcription><follow_up>what",
            " were you",
            " avoiding</follow_up>",
        )

        val body = events.filterIsInstance<ForegroundStreamEvent.FollowUpDelta>().joinToString("") { it.text }
        assertEquals("what were you avoiding", body)
    }

    @Test
    fun `a partial close tag is withheld until disambiguated`() {
        val scanner = ForegroundStreamScanner()

        // Chunk ends mid close-tag: the trailing "</follow" must not surface as body text.
        val mid = scanner.feed("<follow_up>keep going</follow")
        val end = scanner.accept("_up>")

        val midBody = mid.filterIsInstance<ForegroundStreamEvent.FollowUpDelta>().joinToString("") { it.text }
        val endBody = end.filterIsInstance<ForegroundStreamEvent.FollowUpDelta>().joinToString("") { it.text }
        assertFalse(midBody.contains("</follow"), "partial close tag must be held back")
        assertEquals("keep going", midBody + endBody)
    }

    @Test
    fun `no follow-up tag yields no follow-up deltas`() {
        val scanner = ForegroundStreamScanner()

        val events = scanner.feed("<transcription>only this</transcription> then prose, no follow up tag")

        assertTrue(events.none { it is ForegroundStreamEvent.FollowUpDelta })
    }

    @Test
    fun `accumulated returns the full buffer for the terminal parser`() {
        val scanner = ForegroundStreamScanner()
        val raw = "<transcription>a</transcription><follow_up>b</follow_up>"

        scanner.feed("<transcription>a</transcription>", "<follow_up>b</follow_up>")

        assertEquals(raw, scanner.accumulated)
    }

    @Test
    fun `follow-up close tag bounds the body — text after it is not emitted`() {
        val scanner = ForegroundStreamScanner()

        val events = scanner.feed("<follow_up>bounded</follow_up>trailing junk the model added")

        val body = events.filterIsInstance<ForegroundStreamEvent.FollowUpDelta>().joinToString("") { it.text }
        assertEquals("bounded", body)
    }
}
