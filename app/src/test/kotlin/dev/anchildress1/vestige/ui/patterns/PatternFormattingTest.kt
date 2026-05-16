package dev.anchildress1.vestige.ui.patterns

import dev.anchildress1.vestige.model.PatternState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
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

    // region actionSnackbarMessageRes

    @Test
    fun `actionSnackbarMessageRes maps DROP to the dismissed snackbar resource`() {
        assertEquals(
            dev.anchildress1.vestige.R.string.snackbar_dismissed,
            actionSnackbarMessageRes(PatternAction.DROP),
        )
    }

    @Test
    fun `actionSnackbarMessageRes maps SKIP to the 7-day snackbar resource`() {
        assertEquals(
            dev.anchildress1.vestige.R.string.snackbar_snoozed_7_days,
            actionSnackbarMessageRes(PatternAction.SKIP),
        )
    }

    @Test
    fun `actionSnackbarMessageRes maps RESTART to the pattern-is-back snackbar resource`() {
        assertEquals(
            dev.anchildress1.vestige.R.string.snackbar_pattern_back,
            actionSnackbarMessageRes(PatternAction.RESTART),
        )
    }

    @Test
    fun `actionSnackbarMessageRes covers every PatternAction variant`() {
        // Guards silent enum extension — the day someone adds a new action, this test fails
        // until the mapping is updated.
        PatternAction.entries.forEach { action ->
            actionSnackbarMessageRes(action)
        }
    }

    // endregion

    // region undoLabelResFor

    @Test
    fun `undoLabelResFor returns the Undo resource when the undo payload is present`() {
        assertEquals(
            dev.anchildress1.vestige.R.string.pattern_undo,
            undoLabelResFor(PatternUndo("any-id", PatternAction.DROP)),
        )
    }

    @Test
    fun `undoLabelResFor returns null when the action is terminal`() {
        assertNull(undoLabelResFor(null))
    }

    // endregion

    // region emptyCopyFor

    @Test
    fun `emptyCopyFor NO_ENTRIES returns the Day-1 eyebrow header and body resources`() {
        val copy = emptyCopyFor(PatternsListUiState.EmptyReason.NO_ENTRIES)
        assertEquals(dev.anchildress1.vestige.R.string.patterns_empty_day1_eyebrow, copy.eyebrowRes)
        assertEquals(dev.anchildress1.vestige.R.string.patterns_empty_day1_header, copy.headerRes)
        assertEquals(dev.anchildress1.vestige.R.string.patterns_empty_day1_body, copy.bodyRes)
    }

    @Test
    fun `emptyCopyFor NO_PATTERNS returns a null eyebrow with the none header and body`() {
        val copy = emptyCopyFor(PatternsListUiState.EmptyReason.NO_PATTERNS)
        assertNull(copy.eyebrowRes)
        assertEquals(dev.anchildress1.vestige.R.string.patterns_empty_none_header, copy.headerRes)
        assertEquals(dev.anchildress1.vestige.R.string.patterns_empty_none_body, copy.bodyRes)
    }

    @Test
    fun `emptyCopyFor covers every EmptyReason variant with a non-zero header and body`() {
        PatternsListUiState.EmptyReason.entries.forEach { reason ->
            val copy = emptyCopyFor(reason)
            assertNotEquals(0, copy.headerRes)
            assertNotEquals(0, copy.bodyRes)
        }
    }

    // endregion

    // region terminalLabelFor

    @Test
    fun `terminalLabelFor CLOSED returns the resolved prefix dated label and a non-null day span`() {
        val lastSeen = Instant.parse("2026-05-10T12:00:00Z").toEpochMilli()
        // Closed two full days + 6h after last sighting — floor((stateChanged-lastSeen)/day) == 2.
        val stateChanged = Instant.parse("2026-05-12T18:00:00Z").toEpochMilli()
        val label = terminalLabelFor(PatternState.CLOSED, stateChanged, lastSeen, ZoneOffset.UTC)
        assertEquals(dev.anchildress1.vestige.R.string.pattern_terminal_resolved, label?.prefixRes)
        assertEquals("May 12", label?.dateLabel)
        assertEquals(2, label?.days)
    }

    @Test
    fun `terminalLabelFor CLOSED coerces a negative day span to zero`() {
        // lastSeen after stateChanged (clock skew) — span must floor at 0, never go negative.
        val stateChanged = Instant.parse("2026-05-12T12:00:00Z").toEpochMilli()
        val lastSeen = Instant.parse("2026-05-13T12:00:00Z").toEpochMilli()
        val label = terminalLabelFor(PatternState.CLOSED, stateChanged, lastSeen, ZoneOffset.UTC)
        assertEquals(0, label?.days)
    }

    @Test
    fun `terminalLabelFor DROPPED returns the dismissed prefix dated label and a null day span`() {
        val ms = Instant.parse("2026-05-12T12:00:00Z").toEpochMilli()
        val label = terminalLabelFor(PatternState.DROPPED, ms, ms, ZoneOffset.UTC)
        assertEquals(dev.anchildress1.vestige.R.string.pattern_terminal_dismissed, label?.prefixRes)
        assertEquals("May 12", label?.dateLabel)
        assertNull(label?.days)
    }

    @Test
    fun `terminalLabelFor returns null for non-terminal states`() {
        val ms = Instant.parse("2026-05-12T12:00:00Z").toEpochMilli()
        assertNull(terminalLabelFor(PatternState.ACTIVE, ms, ms, ZoneOffset.UTC))
        assertNull(terminalLabelFor(PatternState.SNOOZED, ms, ms, ZoneOffset.UTC))
        assertNull(terminalLabelFor(PatternState.BELOW_THRESHOLD, ms, ms, ZoneOffset.UTC))
    }

    @Test
    fun `terminalLabelFor covers every PatternState variant`() {
        PatternState.entries.forEach { state ->
            // No throw on any enum value — the function is total.
            terminalLabelFor(state, 0L, 0L, ZoneOffset.UTC)
        }
    }

    // endregion

    // region availableActionsFor

    @Test
    fun `availableActionsFor ACTIVE exposes Drop and Skip`() {
        assertEquals(
            setOf(PatternAction.DROP, PatternAction.SKIP),
            availableActionsFor(PatternState.ACTIVE),
        )
    }

    @Test
    fun `availableActionsFor non-active visible states expose Restart only`() {
        assertEquals(setOf(PatternAction.RESTART), availableActionsFor(PatternState.SNOOZED))
        assertEquals(setOf(PatternAction.RESTART), availableActionsFor(PatternState.DROPPED))
        assertEquals(setOf(PatternAction.RESTART), availableActionsFor(PatternState.CLOSED))
    }

    @Test
    fun `availableActionsFor BELOW_THRESHOLD exposes nothing`() {
        assertTrue(availableActionsFor(PatternState.BELOW_THRESHOLD).isEmpty())
    }

    @Test
    fun `availableActionsFor covers every PatternState variant`() {
        PatternState.entries.forEach { availableActionsFor(it) }
    }

    // endregion

    // region sectionFor

    @Test
    fun `sectionFor maps every user-visible state to its section`() {
        assertEquals(PatternSection.ACTIVE, sectionFor(PatternState.ACTIVE))
        assertEquals(PatternSection.SKIPPED, sectionFor(PatternState.SNOOZED))
        assertEquals(PatternSection.CLOSED, sectionFor(PatternState.CLOSED))
        assertEquals(PatternSection.DROPPED, sectionFor(PatternState.DROPPED))
    }

    @Test
    fun `sectionFor returns null for the internal-only BELOW_THRESHOLD state`() {
        assertNull(sectionFor(PatternState.BELOW_THRESHOLD))
    }

    @Test
    fun `sectionFor covers every PatternState variant`() {
        PatternState.entries.forEach { sectionFor(it) }
    }

    @Test
    fun `PatternSection declaration order is the spec section P0_4 render order`() {
        // Enum declaration order IS the render order — spec-pattern-action-buttons.md §P0.4
        // (ACTIVE → SKIPPED → CLOSED → DROPPED). A reorder silently scrambles the list.
        assertEquals(
            listOf(
                PatternSection.ACTIVE,
                PatternSection.SKIPPED,
                PatternSection.CLOSED,
                PatternSection.DROPPED,
            ),
            PatternSection.entries,
        )
    }

    // endregion

    // region sectionHeaderRes

    @Test
    fun `sectionHeaderRes maps each section to its header resource`() {
        assertEquals(
            dev.anchildress1.vestige.R.string.patterns_section_active,
            sectionHeaderRes(PatternSection.ACTIVE),
        )
        assertEquals(
            dev.anchildress1.vestige.R.string.patterns_section_snoozed,
            sectionHeaderRes(PatternSection.SKIPPED),
        )
        assertEquals(
            dev.anchildress1.vestige.R.string.patterns_section_resolved,
            sectionHeaderRes(PatternSection.CLOSED),
        )
        assertEquals(
            dev.anchildress1.vestige.R.string.patterns_section_dismissed,
            sectionHeaderRes(PatternSection.DROPPED),
        )
    }

    @Test
    fun `sectionHeaderRes covers every PatternSection variant`() {
        PatternSection.entries.forEach { sectionHeaderRes(it) }
    }

    // endregion

    // region isTerminalState

    @Test
    fun `isTerminalState true for ADR-003 terminal states`() {
        assertTrue(isTerminalState(PatternState.DROPPED))
        assertTrue(isTerminalState(PatternState.CLOSED))
    }

    @Test
    fun `isTerminalState false for recoverable and internal states`() {
        assertFalse(isTerminalState(PatternState.ACTIVE))
        assertFalse(isTerminalState(PatternState.SNOOZED))
        assertFalse(isTerminalState(PatternState.BELOW_THRESHOLD))
    }

    @Test
    fun `isTerminalState covers every PatternState variant`() {
        PatternState.entries.forEach { isTerminalState(it) }
    }

    // endregion
}
