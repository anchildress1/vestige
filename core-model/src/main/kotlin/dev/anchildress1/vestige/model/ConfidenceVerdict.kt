package dev.anchildress1.vestige.model

/** Per-field outcome after the convergence resolver runs. */
enum class ConfidenceVerdict {
    /** ≥2 of 3 lenses agree. Saved as authoritative. */
    CANONICAL,

    /** Only Inferential populated. Lower confidence; not used by the pattern engine. */
    CANDIDATE,

    /** Lenses disagree. Saved null + note. */
    AMBIGUOUS,

    /** ≥2 agree but Skeptical flagged a conflict. */
    CANONICAL_WITH_CONFLICT,
}
