package dev.anchildress1.vestige.model

/**
 * Convergence outcome for a single extracted field per `concept-locked.md` §"Convergence rules".
 * The 3-lens × 5-surface pipeline (Phase 2) reduces three lens passes to one verdict per field.
 */
enum class ConfidenceVerdict {
    /** ≥2 of 3 lenses agree on the field. Saved as authoritative. */
    CANONICAL,

    /**
     * Only Inferential populated the field. Saved at lower confidence; not used by the pattern
     * engine until promoted.
     */
    CANDIDATE,

    /** Lenses disagree. Saved null with a note so re-eval can revisit. */
    AMBIGUOUS,

    /**
     * ≥2 lenses agree but Skeptical flagged a conflict. Saved as canonical with a conflict
     * marker.
     */
    CANONICAL_WITH_CONFLICT,
}
