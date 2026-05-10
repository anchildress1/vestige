package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.Lens
import dev.anchildress1.vestige.model.LensExtraction

/**
 * One pass through `(lens, all five surfaces)`. [extraction] is `null` when every attempt
 * against this lens failed; convergence treats that as "no opinion." [attemptCount] is per-lens
 * diagnostic data, not the persisted entry retry counter.
 */
data class LensResult(
    val lens: Lens,
    val extraction: LensExtraction?,
    val rawResponse: String,
    val attemptCount: Int,
    val elapsedMs: Long,
    val lastError: String?,
)
