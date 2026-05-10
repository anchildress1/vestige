package dev.anchildress1.vestige.model

/** Three orthogonal lens framings — each runs the same five-surface extraction. */
enum class Lens {
    /** Only what the entry text explicitly says. */
    LITERAL,

    /** Explicit + reasonable contextual inference. */
    INFERENTIAL,

    /** Adversarial — surface contradictions, missing pieces, things that don't add up. */
    SKEPTICAL,
}
