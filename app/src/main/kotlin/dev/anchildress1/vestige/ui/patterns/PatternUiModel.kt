package dev.anchildress1.vestige.ui.patterns

import dev.anchildress1.vestige.model.PatternState

/** Card row payload for the Patterns list. Pure UI — no ObjectBox handles leak past the VM. */
data class PatternCardUi(
    val patternId: String,
    val title: String,
    val templateLabel: String?,
    val observation: String,
    val supportingCount: Int,
    val totalEntryCount: Long,
    val lastSeenLabel: String,
    val section: PatternSection,
    /** Indices 0..days-1 over the trailing 30-day window. See [traceBarHits]. */
    val traceHits: Set<Int>,
    /** UI must only surface actions the lifecycle state machine will accept. */
    val availableActions: Set<PatternAction>,
    /** `Back {date}` wake-up line — non-null only for [PatternSection.SKIPPED] cards. */
    val backLabel: String? = null,
) {
    init {
        check(backLabel == null || section == PatternSection.SKIPPED) {
            "backLabel must be null for non-SKIPPED cards (section=$section)"
        }
    }
}

/**
 * Status grouping for the Patterns list per `ux-copy.md` §"Pattern List / Section headers".
 * Declaration order is the fixed render order (`spec-pattern-action-buttons.md` §P0.4):
 * ACTIVE → SKIPPED → CLOSED → DROPPED. The persisted `SNOOZED` state maps to the [SKIPPED]
 * section (user-facing label is "Skip"); internal `BELOW_THRESHOLD` is never user-visible.
 */
enum class PatternSection { ACTIVE, SKIPPED, CLOSED, DROPPED }

/**
 * Source row for the Pattern detail. [snippet] is the leading slice of the supporting entry's
 * text so the user can match the date to the moment without leaving the screen.
 */
data class PatternSourceUi(val entryId: Long, val dateLabel: String, val snippet: String)

sealed interface PatternsListUiState {
    object Loading : PatternsListUiState

    /** [entryCount] feeds the Day-1 eyebrow `VESTIGES · {n} ENTRIES · 30 DAYS`. */
    data class Empty(val reason: EmptyReason, val entryCount: Int) : PatternsListUiState {
        init {
            require(entryCount >= 0) { "entryCount must be non-negative" }
        }
    }
    data class Loaded(val cards: List<PatternCardUi>) : PatternsListUiState

    enum class EmptyReason {
        /** Fewer than the detection threshold of entries — the Day-1 "keep recording" state. */
        NO_ENTRIES,

        /** Enough entries, detector found nothing repeating. */
        NO_PATTERNS,
    }
}

sealed interface PatternDetailUiState {
    object Loading : PatternDetailUiState
    object NotFound : PatternDetailUiState
    data class Loaded(
        val patternId: String,
        val title: String,
        val templateLabel: String?,
        val observation: String,
        val supportingCount: Int,
        val totalEntryCount: Long,
        val lastSeenLabel: String,
        val sources: List<PatternSourceUi>,
        val traceHits: Set<Int>,
        val state: PatternState,
        val isTerminal: Boolean,
        val terminalLabel: TerminalLabel?,
        val availableActions: Set<PatternAction>,
    ) : PatternDetailUiState
}

/**
 * User-driven pattern actions. `Skip` persists as `PatternState.SNOOZED` (the user label is
 * "Skip", the state name stays `SNOOZED` per ADR-003 Addendum 2026-05-13). There is no
 * user-facing close action — closure is model-detected only (`pattern-auto-close`).
 */
enum class PatternAction { DROP, SKIP, RESTART }

/** One-shot snackbar payload shared by the list and detail surfaces. */
data class PatternActionEvent(val patternId: String, val action: PatternAction, val undo: PatternUndo?)

/** Card-action callbacks bundled so composables stay within detekt's parameter ceiling. */
data class PatternActionCallbacks<T>(val onDrop: (T) -> Unit, val onSkip: (T) -> Unit, val onRestart: (T) -> Unit = {})

/**
 * Inverse-action payload the snackbar reissues if the user taps `Undo` while it's alive.
 * [previousState] / [previousSnoozedUntil] are non-null only for RESTART so the undo path can
 * restore the exact pre-restart snapshot.
 */
data class PatternUndo(
    val patternId: String,
    val action: PatternAction,
    val previousState: PatternState? = null,
    val previousSnoozedUntil: Long? = null,
)
