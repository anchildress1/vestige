package dev.anchildress1.vestige.model

/**
 * Three orthogonal lens framings per `concept-locked.md` §"Multi-lens extraction architecture".
 * Each lens runs the same five-surface extraction; the convergence resolver reduces the three
 * outputs to one verdict per field.
 */
enum class Lens {
    /** Strict; only what's explicit in the entry text. */
    LITERAL,

    /** Charitable; explicit + reasonable contextual inference. */
    INFERENTIAL,

    /** Adversarial; flag contradictions, missing pieces, what doesn't add up. */
    SKEPTICAL,
}
