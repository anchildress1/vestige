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

private const val MILLIS_PER_DAY = 86_400_000L

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
    // Resource keys keep their legacy `*_dismissed` / `*_snoozed` names (see strings.xml note);
    // the rendered copy is "Dropped." / "Skipped." / "Pattern is back." per ux-copy.md.
    PatternAction.DROP -> R.string.snackbar_dismissed

    PatternAction.SKIP -> R.string.snackbar_snoozed_7_days

    PatternAction.RESTART -> R.string.snackbar_pattern_back
}

/** `null` when the action carries no undo control; otherwise the `Undo` resource id. */
@StringRes
fun undoLabelResFor(undo: PatternUndo?): Int? = if (undo == null) null else R.string.pattern_undo

/** Structured empty-state copy per `ux-copy.md` §"Pattern List / Empty states". */
data class PatternEmptyCopy(
    @field:StringRes val eyebrowRes: Int?,
    @field:StringRes val headerRes: Int,
    @field:StringRes val bodyRes: Int,
)

fun emptyCopyFor(reason: PatternsListUiState.EmptyReason): PatternEmptyCopy = when (reason) {
    PatternsListUiState.EmptyReason.NO_ENTRIES -> PatternEmptyCopy(
        eyebrowRes = R.string.patterns_empty_day1_eyebrow,
        headerRes = R.string.patterns_empty_day1_header,
        bodyRes = R.string.patterns_empty_day1_body,
    )

    PatternsListUiState.EmptyReason.NO_PATTERNS -> PatternEmptyCopy(
        eyebrowRes = null,
        headerRes = R.string.patterns_empty_none_header,
        bodyRes = R.string.patterns_empty_none_body,
    )
}

/**
 * Terminal-state subline payload for the pattern detail screen. Returns `null` for non-terminal
 * states (the action row renders instead). [days] is non-null only for `CLOSED` — it feeds the
 * `No new entries matched in {N} days` clause and is the span from last sighting to closure.
 */
fun terminalLabelFor(
    state: PatternState,
    stateChangedMs: Long,
    lastSeenMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): TerminalLabel? = when (state) {
    PatternState.CLOSED -> TerminalLabel(
        prefixRes = R.string.pattern_terminal_resolved,
        dateLabel = formatShortDate(stateChangedMs, zone),
        days = ((stateChangedMs - lastSeenMs).coerceAtLeast(0) / MILLIS_PER_DAY).toInt(),
    )

    PatternState.DROPPED -> TerminalLabel(
        prefixRes = R.string.pattern_terminal_dismissed,
        dateLabel = formatShortDate(stateChangedMs, zone),
    )

    PatternState.ACTIVE, PatternState.SNOOZED, PatternState.BELOW_THRESHOLD -> null
}

/** Format-string id + args for the terminal-state subline. [days] non-null only for `CLOSED`. */
data class TerminalLabel(@field:StringRes val prefixRes: Int, val dateLabel: String, val days: Int? = null)

/** ADR-003 terminals: DROPPED (user) + CLOSED (model). SNOOZED is recoverable; BELOW_THRESHOLD internal. */
fun isTerminalState(state: PatternState): Boolean = state == PatternState.DROPPED || state == PatternState.CLOSED

/**
 * Per `spec-pattern-action-buttons.md` §P0.1–P0.3: ACTIVE patterns expose Drop + Skip; every
 * other user-visible state exposes Restart. The detail screen separately suppresses the action
 * row for `CLOSED` (read-only, model-detected) — that gate lives at the call site, not here, so
 * the list-card overflow can still Restart a (v1.5) closed card.
 */
fun availableActionsFor(state: PatternState): Set<PatternAction> = when (state) {
    PatternState.ACTIVE -> setOf(PatternAction.DROP, PatternAction.SKIP)

    PatternState.SNOOZED,
    PatternState.DROPPED,
    PatternState.CLOSED,
    -> setOf(PatternAction.RESTART)

    PatternState.BELOW_THRESHOLD -> emptySet()
}

/**
 * Map a persisted [PatternState] onto the Patterns-list section it belongs in. Returns `null`
 * for [PatternState.BELOW_THRESHOLD] — internal-only, never user-visible. `SNOOZED` maps to the
 * SKIPPED section (user label is "Skip"; state name stays `SNOOZED` per ADR-003).
 */
fun sectionFor(state: PatternState): PatternSection? = when (state) {
    PatternState.ACTIVE -> PatternSection.ACTIVE
    PatternState.SNOOZED -> PatternSection.SKIPPED
    PatternState.CLOSED -> PatternSection.CLOSED
    PatternState.DROPPED -> PatternSection.DROPPED
    PatternState.BELOW_THRESHOLD -> null
}

/** Section header string resource per `ux-copy.md` §"Pattern List / Section headers". */
@StringRes
fun sectionHeaderRes(section: PatternSection): Int = when (section) {
    // Resource keys keep legacy names; rendered values are ACTIVE / SKIPPED · ON HOLD /
    // CLOSED · DONE / DROPPED per strings.xml.
    PatternSection.ACTIVE -> R.string.patterns_section_active

    PatternSection.SKIPPED -> R.string.patterns_section_snoozed

    PatternSection.CLOSED -> R.string.patterns_section_resolved

    PatternSection.DROPPED -> R.string.patterns_section_dismissed
}

private const val MAX_SNIPPET_LEN = 60
