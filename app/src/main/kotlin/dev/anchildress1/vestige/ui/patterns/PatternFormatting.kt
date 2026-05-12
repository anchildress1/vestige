package dev.anchildress1.vestige.ui.patterns

import dev.anchildress1.vestige.model.PatternState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Short, ND-friendly date format for cards + source rows. Avoids the year — pattern cadence is recent. */
private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)

fun formatShortDate(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    SHORT_DATE.format(Instant.ofEpochMilli(epochMs).atZone(zone))

/** Trimmed leading slice of an entry — caps the source snippet so cards stay scannable. */
fun snippetOf(entryText: String, maxLen: Int = MAX_SNIPPET_LEN): String {
    require(maxLen > 0) { "snippetOf maxLen must be > 0 (got $maxLen)" }
    val collapsed = entryText.replace('\n', ' ').trim()
    if (collapsed.length <= maxLen) return collapsed
    val cut = collapsed.substring(0, maxLen).trimEnd()
    return "$cut…"
}

/** Snackbar copy per `ux-copy.md` §"System Messages" mapped from the dispatched action. */
fun actionSnackbarMessage(action: PatternAction): String = when (action) {
    PatternAction.DISMISSED -> "Dismissed."
    PatternAction.SNOOZED -> "Snoozed 7 days."
    PatternAction.MARKED_RESOLVED -> "Marked resolved."
}

/** `null` when the action is terminal (no undo control); `"Undo"` otherwise. */
fun undoLabelFor(undo: PatternUndo?): String? = if (undo == null) null else "Undo"

/** Empty-state copy per `ux-copy.md` §"Pattern List / Empty states". */
fun emptyStateCopy(reason: PatternsListUiState.EmptyReason): String = when (reason) {
    PatternsListUiState.EmptyReason.NO_ENTRIES -> "Insufficient data."
    PatternsListUiState.EmptyReason.NO_PATTERNS -> "Nothing repeating yet."
}

/**
 * Terminal-state subline shown on the pattern detail screen. Returns `null` for non-terminal
 * states (the action row renders instead).
 */
fun terminalLabelFor(state: PatternState, stateChangedMs: Long, zone: ZoneId = ZoneId.systemDefault()): String? =
    when (state) {
        PatternState.RESOLVED -> "Marked resolved ${formatShortDate(stateChangedMs, zone)}."
        PatternState.DISMISSED -> "Dismissed ${formatShortDate(stateChangedMs, zone)}."
        PatternState.ACTIVE, PatternState.SNOOZED, PatternState.BELOW_THRESHOLD -> null
    }

/** ADR-003 terminals: DISMISSED + RESOLVED. SNOOZED is recoverable; BELOW_THRESHOLD is internal. */
fun isTerminalState(state: PatternState): Boolean = state == PatternState.DISMISSED || state == PatternState.RESOLVED

/**
 * Map a persisted [PatternState] onto the Patterns-list section it belongs in. Returns `null`
 * for [PatternState.BELOW_THRESHOLD] — that's an internal-only state and never user-visible.
 */
fun sectionFor(state: PatternState): PatternSection? = when (state) {
    PatternState.ACTIVE -> PatternSection.ACTIVE
    PatternState.SNOOZED -> PatternSection.SNOOZED
    PatternState.RESOLVED -> PatternSection.RESOLVED
    PatternState.DISMISSED -> PatternSection.DISMISSED
    PatternState.BELOW_THRESHOLD -> null
}

private const val MAX_SNIPPET_LEN = 60
