package dev.anchildress1.vestige.ui.patterns

import androidx.annotation.StringRes
import dev.anchildress1.vestige.R
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

/** Snackbar copy string-resource per `ux-copy.md` §"System Messages". UI resolves via `stringResource`. */
@StringRes
fun actionSnackbarMessageRes(action: PatternAction): Int = when (action) {
    PatternAction.DISMISSED -> R.string.snackbar_dismissed
    PatternAction.SNOOZED -> R.string.snackbar_snoozed_7_days
    PatternAction.MARKED_RESOLVED -> R.string.snackbar_marked_resolved
}

/** `null` when the action is terminal (no undo control); otherwise the `Undo` resource id. */
@StringRes
fun undoLabelResFor(undo: PatternUndo?): Int? = if (undo == null) null else R.string.pattern_undo

/** Empty-state copy resource per `ux-copy.md` §"Pattern List / Empty states". */
@StringRes
fun emptyStateCopyRes(reason: PatternsListUiState.EmptyReason): Int = when (reason) {
    PatternsListUiState.EmptyReason.NO_ENTRIES -> R.string.patterns_empty_no_entries
    PatternsListUiState.EmptyReason.NO_PATTERNS -> R.string.patterns_empty_no_patterns
}

/**
 * Terminal-state subline payload for the pattern detail screen. Returns `null` for non-terminal
 * states (the action row renders instead). UI composes the final string with
 * `stringResource(prefixRes, dateLabel)`.
 */
fun terminalLabelFor(
    state: PatternState,
    stateChangedMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): TerminalLabel? = when (state) {
    PatternState.RESOLVED -> TerminalLabel(R.string.pattern_terminal_resolved, formatShortDate(stateChangedMs, zone))
    PatternState.DISMISSED -> TerminalLabel(R.string.pattern_terminal_dismissed, formatShortDate(stateChangedMs, zone))
    PatternState.ACTIVE, PatternState.SNOOZED, PatternState.BELOW_THRESHOLD -> null
}

/** Format-string id + date payload for the terminal-state subline. */
data class TerminalLabel(@StringRes val prefixRes: Int, val dateLabel: String)

/** ADR-003 terminals: DISMISSED + RESOLVED. SNOOZED is recoverable; BELOW_THRESHOLD is internal. */
fun isTerminalState(state: PatternState): Boolean = state == PatternState.DISMISSED || state == PatternState.RESOLVED

/** Only expose actions the persisted lifecycle can legally accept from the current state. */
fun availableActionsFor(state: PatternState): Set<PatternAction> = when (state) {
    PatternState.ACTIVE -> setOf(
        PatternAction.DISMISSED,
        PatternAction.SNOOZED,
        PatternAction.MARKED_RESOLVED,
    )

    PatternState.SNOOZED -> setOf(PatternAction.DISMISSED)

    PatternState.DISMISSED, PatternState.RESOLVED, PatternState.BELOW_THRESHOLD -> emptySet()
}

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

/** Section header string resource per `ux-copy.md` §"Pattern List / Section headers". */
@StringRes
fun sectionHeaderRes(section: PatternSection): Int = when (section) {
    PatternSection.ACTIVE -> R.string.patterns_section_active
    PatternSection.SNOOZED -> R.string.patterns_section_snoozed
    PatternSection.RESOLVED -> R.string.patterns_section_resolved
    PatternSection.DISMISSED -> R.string.patterns_section_dismissed
}

private const val MAX_SNIPPET_LEN = 60
