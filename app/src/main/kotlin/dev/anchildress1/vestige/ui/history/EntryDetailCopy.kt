package dev.anchildress1.vestige.ui.history

/** Entry Detail microcopy. Single source of truth is docs/ux-copy.md §"Entry detail" + §"Destructive Confirmations". */
object EntryDetailCopy {
    const val FILED_EYEBROW_PREFIX = "FILED"
    const val ENTRY_NUMBER_PREFIX = "ENTRY #"

    const val AUDIO_STAT_LABEL = "AUDIO"
    const val WORDS_STAT_LABEL = "WORDS"

    const val YOU_LABEL = "YOU · TRANSCRIPT"
    const val READING_LABEL_SUFFIX = "· READING"

    const val OVERFLOW_DELETE = "Delete entry"

    const val DELETE_TITLE = "Delete this entry?"
    const val DELETE_BODY =
        "The entry, its transcription, and any tags extracted from it. Patterns referencing it will be recalculated."
    const val DELETE_CONFIRM = "Delete"
    const val DELETE_CANCEL = "Cancel"

    const val BACK_LABEL = "←"
    const val BACK_CD = "Back"
    const val NEW_ENTRY_LABEL = "● NEW ENTRY"
    const val NEW_ENTRY_CD = "New entry"

    const val OVERFLOW_CD = "Entry actions"

    const val NOT_FOUND = "Entry not found."
}
