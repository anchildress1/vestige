package dev.anchildress1.vestige.model

/**
 * Lifecycle of the background extraction pass on each `Entry` row. Markdown rebuilds skip this
 * — markdown-only entries are `COMPLETED` by definition.
 */
enum class ExtractionStatus {
    /** Foreground committed the entry; background hasn't started. */
    PENDING,

    /** Background running. A cold start that finds this implies a kill mid-flight. */
    RUNNING,

    /** Convergence resolver finished and the canonical fields are written. */
    COMPLETED,

    /** Per-entry timeout exceeded. */
    TIMED_OUT,

    /** Pass failed; `last_error` carries the reason. Retry budget capped at 3. */
    FAILED,
}
