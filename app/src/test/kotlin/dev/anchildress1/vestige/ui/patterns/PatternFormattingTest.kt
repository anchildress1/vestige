package dev.anchildress1.vestige.ui.patterns

import dev.anchildress1.vestige.model.PatternState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

class PatternFormattingTest {

    // region formatShortDate

    @Test
    fun `formatShortDate emits MMM d in the given zone`() {
        val ms = Instant.parse("2026-05-12T12:00:00Z").toEpochMilli()
        assertEquals("May 12", formatShortDate(ms, ZoneOffset.UTC))
    }

    @Test
    fun `formatShortDate honours non-UTC zone boundaries`() {
        // 23:30 UTC on the 12th is 02:30 on the 13th in Asia/Tokyo (UTC+9).
        val ms = Instant.parse("2026-05-12T23:30:00Z").toEpochMilli()
        assertEquals("May 13", formatShortDate(ms, ZoneOffset.ofHours(9)))
    }

    @Test
    fun `formatShortDate handles epoch zero without throwing`() {
        assertEquals("Jan 1", formatShortDate(0L, ZoneOffset.UTC))
    }

    // endregion

    // region snippetOf

    @Test
    fun `snippetOf preserves short text unchanged`() {
        assertEquals("hello world", snippetOf("hello world"))
    }

    @Test
    fun `snippetOf truncates long text with an ellipsis`() {
        val input = "a".repeat(80)
        val snippet = snippetOf(input)
        assertTrue(snippet.endsWith("…"))
        // 60-char body + 1-char ellipsis.
        assertEquals(61, snippet.length)
    }

    @Test
    fun `snippetOf collapses newlines into spaces`() {
        assertEquals("hello world", snippetOf("hello\nworld"))
    }

    @Test
    fun `snippetOf trims leading and trailing whitespace`() {
        assertEquals("hello", snippetOf("   hello   "))
    }

    @Test
    fun `snippetOf trims whitespace before appending the ellipsis`() {
        // 59 chars + a trailing space + a long tail — the trim step must strip the space so the
        // ellipsis joins the word, not a hanging space.
        val body = "a".repeat(59) + " " + "tail".repeat(20)
        val snippet = snippetOf(body)
        assertEquals("a".repeat(59) + "…", snippet)
    }

    @Test
    fun `snippetOf accepts an explicit maxLen and uses it`() {
        assertEquals("hello…", snippetOf("hello world", maxLen = 5))
    }

    @Test
    fun `snippetOf at exact boundary returns the full string without ellipsis`() {
        val input = "a".repeat(60)
        assertEquals(input, snippetOf(input))
    }

    @Test
    fun `snippetOf rejects a non-positive maxLen`() {
        assertThrows(IllegalArgumentException::class.java) { snippetOf("hello", maxLen = 0) }
        assertThrows(IllegalArgumentException::class.java) { snippetOf("hello", maxLen = -1) }
    }

    @Test
    fun `snippetOf returns an empty string for blank input`() {
        assertEquals("", snippetOf(""))
        assertEquals("", snippetOf("   \n\t  "))
    }

    // endregion

    // region actionSnackbarMessage

    @Test
    fun `actionSnackbarMessage maps DISMISSED to past-tense one-liner`() {
        assertEquals("Dismissed.", actionSnackbarMessage(PatternAction.DISMISSED))
    }

    @Test
    fun `actionSnackbarMessage maps SNOOZED to the 7-day copy`() {
        assertEquals("Snoozed 7 days.", actionSnackbarMessage(PatternAction.SNOOZED))
    }

    @Test
    fun `actionSnackbarMessage maps MARKED_RESOLVED to past-tense one-liner`() {
        assertEquals("Marked resolved.", actionSnackbarMessage(PatternAction.MARKED_RESOLVED))
    }

    @Test
    fun `actionSnackbarMessage covers every PatternAction variant`() {
        // Guards silent enum extension — the day someone adds a new action, this test fails
        // until the mapping is updated.
        PatternAction.entries.forEach { action ->
            assertNotNull(actionSnackbarMessage(action), "no snackbar copy for $action")
        }
    }

    // endregion

    // region undoLabelFor

    @Test
    fun `undoLabelFor returns Undo when the undo payload is present`() {
        assertEquals("Undo", undoLabelFor(PatternUndo("any-id", PatternAction.DISMISSED)))
    }

    @Test
    fun `undoLabelFor returns null when the action is terminal`() {
        assertNull(undoLabelFor(null))
    }

    // endregion

    // region emptyStateCopy

    @Test
    fun `emptyStateCopy NO_ENTRIES maps to insufficient-data copy`() {
        assertEquals("Insufficient data.", emptyStateCopy(PatternsListUiState.EmptyReason.NO_ENTRIES))
    }

    @Test
    fun `emptyStateCopy NO_PATTERNS maps to nothing-repeating copy`() {
        assertEquals("Nothing repeating yet.", emptyStateCopy(PatternsListUiState.EmptyReason.NO_PATTERNS))
    }

    @Test
    fun `emptyStateCopy covers every EmptyReason variant`() {
        PatternsListUiState.EmptyReason.entries.forEach { reason ->
            assertNotNull(emptyStateCopy(reason), "no empty-state copy for $reason")
        }
    }

    // endregion

    // region terminalLabelFor

    @Test
    fun `terminalLabelFor RESOLVED renders the resolution date`() {
        val ms = Instant.parse("2026-05-12T12:00:00Z").toEpochMilli()
        assertEquals(
            "Marked resolved May 12.",
            terminalLabelFor(PatternState.RESOLVED, ms, ZoneOffset.UTC),
        )
    }

    @Test
    fun `terminalLabelFor DISMISSED renders the dismissal date`() {
        val ms = Instant.parse("2026-05-12T12:00:00Z").toEpochMilli()
        assertEquals(
            "Dismissed May 12.",
            terminalLabelFor(PatternState.DISMISSED, ms, ZoneOffset.UTC),
        )
    }

    @Test
    fun `terminalLabelFor returns null for non-terminal states`() {
        val ms = Instant.parse("2026-05-12T12:00:00Z").toEpochMilli()
        assertNull(terminalLabelFor(PatternState.ACTIVE, ms, ZoneOffset.UTC))
        assertNull(terminalLabelFor(PatternState.SNOOZED, ms, ZoneOffset.UTC))
        assertNull(terminalLabelFor(PatternState.BELOW_THRESHOLD, ms, ZoneOffset.UTC))
    }

    @Test
    fun `terminalLabelFor covers every PatternState variant`() {
        val ms = 0L
        PatternState.entries.forEach { state ->
            // No throw on any enum value — the function is total.
            terminalLabelFor(state, ms, ZoneOffset.UTC)
        }
    }

    // endregion

    // region sectionFor

    @Test
    fun `sectionFor maps every user-visible state to its section`() {
        assertEquals(PatternSection.ACTIVE, sectionFor(PatternState.ACTIVE))
        assertEquals(PatternSection.SNOOZED, sectionFor(PatternState.SNOOZED))
        assertEquals(PatternSection.RESOLVED, sectionFor(PatternState.RESOLVED))
        assertEquals(PatternSection.DISMISSED, sectionFor(PatternState.DISMISSED))
    }

    @Test
    fun `sectionFor returns null for the internal-only BELOW_THRESHOLD state`() {
        assertNull(sectionFor(PatternState.BELOW_THRESHOLD))
    }

    @Test
    fun `sectionFor covers every PatternState variant`() {
        PatternState.entries.forEach { sectionFor(it) }
    }

    // endregion

    // region isTerminalState

    @Test
    fun `isTerminalState true for ADR-003 terminal states`() {
        assertTrue(isTerminalState(PatternState.DISMISSED))
        assertTrue(isTerminalState(PatternState.RESOLVED))
    }

    @Test
    fun `isTerminalState false for recoverable and internal states`() {
        assertFalse(isTerminalState(PatternState.ACTIVE))
        assertFalse(isTerminalState(PatternState.SNOOZED))
        assertFalse(isTerminalState(PatternState.BELOW_THRESHOLD))
    }

    // endregion
}
