package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.ResolvedExtraction

/**
 * Outcome of one background 3-lens pass per Story 2.6. The worker emits exactly one of these per
 * entry. [Success] carries the [ResolvedExtraction] from the convergence resolver and at least one
 * lens that parsed cleanly (per ADR-002 §"Convergence edge cases", a lens that failed every attempt
 * contributes "no opinion" to convergence — it does not block the result). [Failed] is reserved for
 * the case where every lens exhausted its retry budget; convergence is not invoked.
 *
 * `totalElapsedMs` measures wall time from the first lens call through resolver completion (or
 * the last failed lens). `attemptCount` totals model calls across all lenses for the entry's
 * `attempt_count` field (ADR-001 §Q3).
 */
sealed interface BackgroundExtractionResult {
    val totalElapsedMs: Long
    val lensResults: List<LensResult>
    val attemptCount: Int

    data class Success(
        override val totalElapsedMs: Long,
        override val lensResults: List<LensResult>,
        override val attemptCount: Int,
        val resolved: ResolvedExtraction,
    ) : BackgroundExtractionResult

    data class Failed(
        override val totalElapsedMs: Long,
        override val lensResults: List<LensResult>,
        override val attemptCount: Int,
        val lastError: String,
    ) : BackgroundExtractionResult
}
