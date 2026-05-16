package dev.anchildress1.vestige.ui.history

/** One-shot route request for opening entry detail from another surface or launch source. */
data class EntryDetailOpenRequest(val entryId: Long, val highlightOnOpen: Boolean = false, val token: Long)
