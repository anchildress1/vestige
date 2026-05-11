package dev.anchildress1.vestige.model

/**
 * Result of one pass through the pattern detector. Title + callout text are populated by the
 * orchestrator before persistence (those need a model call); detection itself is deterministic
 * counting per ADR-003 §"Detection algorithm".
 */
data class DetectedPattern(
    /** SHA-256 hex of `signatureJson` per ADR-003 §"`pattern_id` generation". */
    val patternId: String,
    val kind: PatternKind,
    /** Canonical JSON bytes that were hashed — stored alongside `patternId` for audit. */
    val signatureJson: String,
    /** Denormalized template label when the signature carries one; null for vocab + goblin. */
    val templateLabel: String?,
    val supportingEntryIds: List<Long>,
    /** Earliest supporting-entry timestamp (epoch millis). */
    val firstSeenTimestamp: Long,
    /** Latest supporting-entry timestamp (epoch millis). */
    val lastSeenTimestamp: Long,
) {
    val supportingEntryCount: Int get() = supportingEntryIds.size
}
