package dev.anchildress1.vestige.model

/**
 * One persisted observation about a single entry. Per `concept-locked.md` §"Analysis (two-layer)":
 * 1–2 of these land on every entry, generated after convergence, never freeform speculation.
 *
 * Every observation must carry evidence — either a quoted snippet from `entry_text` or a
 * structured-field reference in [fields]. The `pattern-callout` evidence type only appears in
 * post-threshold appends and is not emitted by the per-entry generator (Story 2.13); pattern-
 * engine assembly owns it.
 */
data class EntryObservation(val text: String, val evidence: ObservationEvidence, val fields: List<String>) {
    init {
        require(text.isNotBlank()) { "EntryObservation.text must be non-blank" }
    }
}

enum class ObservationEvidence(val serial: String) {
    VOCABULARY_CONTRADICTION("vocabulary-contradiction"),
    COMMITMENT_FLAG("commitment-flag"),
    VOLUNTEERED_CONTEXT("volunteered-context"),
    THEME_NOTICING("theme-noticing"),
    PATTERN_CALLOUT("pattern-callout"),
    ;

    companion object {
        fun fromSerial(serial: String): ObservationEvidence? =
            entries.firstOrNull { it.serial.equals(serial, ignoreCase = true) }
    }
}
