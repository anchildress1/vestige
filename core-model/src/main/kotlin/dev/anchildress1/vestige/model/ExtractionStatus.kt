package dev.anchildress1.vestige.model

/**
 * Operational lifecycle of the background 3-lens extraction pass per ADR-001 §Q3. Lives on
 * every `Entry` row. Markdown source-of-truth never carries this — if an entry rebuilds from
 * markdown, status is `COMPLETED` by definition (architecture-brief.md §"Field placement rules").
 */
enum class ExtractionStatus {
    /** Foreground call has committed the entry; background pass has not started yet. */
    PENDING,

    /** Background pass currently running. A cold start that finds this status implies a kill mid-flight. */
    RUNNING,

    /** Background pass finished and convergence resolver wrote canonical/candidate/ambiguous fields. */
    COMPLETED,

    /** Background pass exceeded the per-entry timeout. */
    TIMED_OUT,

    /** Background pass failed; `last_error` carries a compact reason. Retry budget capped at 3. */
    FAILED,
}
