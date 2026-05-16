package dev.anchildress1.vestige.model

/**
 * Lifecycle states per ADR-003 §"Lifecycle & state transitions" as amended by the
 * 2026-05-13 / 2026-05-13b addenda. `BELOW_THRESHOLD` is internal — set by Re-eval recompute
 * and never user-facing. `DROPPED` is the user "Drop" terminal; `CLOSED` is model-detected only
 * (v1.5 `pattern-auto-close`, `backlog.md`) — reserved but unreachable from any user transition
 * in v1. `SNOOZED` carries the user "Skip" semantic; the user-facing label is "Skip" while the
 * persisted state name stays `SNOOZED` per the ADR-003 addendum (the field is `snoozedUntil`).
 */
enum class PatternState(val serial: String) {
    ACTIVE("active"),

    // Serials are the opaque persisted tokens — kept at their pre-rename values so no
    // ObjectBox migration is needed. Constant name carries the new vocabulary; serial does not.
    DROPPED("dismissed"),
    SNOOZED("snoozed"),
    CLOSED("resolved"),
    BELOW_THRESHOLD("below_threshold"),
    ;

    companion object {
        fun fromSerial(serial: String): PatternState? = entries.firstOrNull { it.serial == serial }
    }
}
