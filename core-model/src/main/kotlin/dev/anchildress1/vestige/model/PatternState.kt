package dev.anchildress1.vestige.model

/**
 * Lifecycle states per ADR-003 §"Lifecycle & state transitions". `BELOW_THRESHOLD` is internal —
 * set by Re-eval recompute and never user-facing. `DISMISSED` and `RESOLVED` are terminal in v1.
 */
enum class PatternState(val serial: String) {
    ACTIVE("active"),
    DISMISSED("dismissed"),
    SNOOZED("snoozed"),
    RESOLVED("resolved"),
    BELOW_THRESHOLD("below_threshold"),
    ;

    companion object {
        fun fromSerial(serial: String): PatternState? = entries.firstOrNull { it.serial == serial }
    }
}
