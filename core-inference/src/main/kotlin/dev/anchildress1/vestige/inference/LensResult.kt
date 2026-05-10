package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.Lens
import dev.anchildress1.vestige.model.LensExtraction

/**
 * One pass through `(lens, all five surfaces)` per Story 2.6. [extraction] is `null` when every
 * attempt against this lens failed and the lens contributes "no opinion" to convergence per
 * ADR-002 §"Convergence edge cases" — a parse failure is data the resolver consumes, not a
 * reason to inflate convergence by retrying indefinitely.
 *
 * [attemptCount] is the per-lens count of model calls made (1 on first-try success, up to
 * [BackgroundExtractionWorker.maxAttemptsPerLens] when retries exhaust). It is per-lens
 * diagnostic data only — [BackgroundExtractionWorker] does not roll it up into the
 * caller-supplied entry retry counter (ADR-001 §Q3 `attempt_count`), which stays stable across
 * lens retries within a single sweep.
 */
data class LensResult(
    val lens: Lens,
    val extraction: LensExtraction?,
    val rawResponse: String,
    val attemptCount: Int,
    val elapsedMs: Long,
    val lastError: String?,
)
