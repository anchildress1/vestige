package dev.anchildress1.vestige.ui.history

/** Entry Detail microcopy. Single source of truth is docs/ux-copy.md §"Entry detail". */
object EntryDetailCopy {
    const val FILED_EYEBROW_PREFIX = "FILED"
    const val ENTRY_NUMBER_PREFIX = "ENTRY #"

    const val AUDIO_STAT_LABEL = "AUDIO"
    const val WORDS_STAT_LABEL = "WORDS"

    const val YOU_LABEL = "YOU · TRANSCRIPT"
    const val READING_LABEL_SUFFIX = "· READING"
    const val THREE_LENS_EYEBROW = "● THREE-LENS READ"
    const val THREE_LENS_STATUS_CONFLICT = "CANONICAL · WITH CONFLICT"
    const val THREE_LENS_STATUS_CANONICAL = "CANONICAL"
    const val THREE_LENS_STATUS_CANDIDATE = "CANDIDATE"
    const val THREE_LENS_STATUS_AMBIGUOUS = "AMBIGUOUS"
    const val EXTRACTING_EYEBROW = "● EXTRACTING · 3 LENSES"
    const val EXTRACTING_BODY =
        "Convergence resolves in the background. Open the entry later for the full read."
    const val FAILED_EYEBROW = "● EXTRACTION DID NOT FINISH"
    const val FAILED_BODY = "The transcript was saved, but the structured read did not resolve."
    const val LENS_MISSING = "not run"
    const val LENS_NO_OPINION = "no opinion"
    const val LENS_NO_FIELDS = "no fields"

    const val BACK_LABEL = "←"
    const val BACK_CD = "Back"
    const val NEW_ENTRY_LABEL = "● NEW ENTRY"
    const val NEW_ENTRY_CD = "New entry"

    const val NOT_FOUND = "Entry not found."
}
