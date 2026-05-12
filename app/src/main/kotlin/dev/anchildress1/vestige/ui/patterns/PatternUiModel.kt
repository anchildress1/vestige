package dev.anchildress1.vestige.ui.patterns

/** Card row payload for the Patterns list. Pure UI — no ObjectBox handles leak past the VM. */
data class PatternCardUi(
    val patternId: String,
    val title: String,
    val templateLabel: String?,
    val observation: String,
    val supportingCount: Int,
    val totalEntryCount: Long,
    val lastSeenLabel: String,
)

/**
 * Source row for the Pattern detail. [snippet] is the leading slice of the supporting entry's
 * text so the user can match the date to the moment without leaving the screen.
 */
data class PatternSourceUi(val entryId: Long, val dateLabel: String, val snippet: String)

sealed interface PatternsListUiState {
    data object Loading : PatternsListUiState
    data class Empty(val reason: EmptyReason) : PatternsListUiState
    data class Loaded(val cards: List<PatternCardUi>) : PatternsListUiState

    enum class EmptyReason {
        NO_ENTRIES,
        NO_PATTERNS,
    }
}

sealed interface PatternDetailUiState {
    data object Loading : PatternDetailUiState
    data object NotFound : PatternDetailUiState
    data class Loaded(
        val patternId: String,
        val title: String,
        val templateLabel: String?,
        val observation: String,
        val supportingCount: Int,
        val totalEntryCount: Long,
        val lastSeenLabel: String,
        val sources: List<PatternSourceUi>,
        val isTerminal: Boolean,
        val terminalLabel: String?,
    ) : PatternDetailUiState
}

enum class PatternAction { DISMISSED, SNOOZED, MARKED_RESOLVED }

/** One-shot snackbar payload shared by the list and detail surfaces. */
data class PatternActionEvent(val patternId: String, val action: PatternAction, val undo: PatternUndo?)

/** Card-action callbacks bundled so composables stay within detekt's parameter ceiling. */
data class PatternActionCallbacks<T>(
    val onDismiss: (T) -> Unit,
    val onSnooze: (T) -> Unit,
    val onMarkResolved: (T) -> Unit,
)

/** Inverse-action payload the snackbar reissues if the user taps `Undo` while it's alive. */
data class PatternUndo(val patternId: String, val action: PatternAction)
