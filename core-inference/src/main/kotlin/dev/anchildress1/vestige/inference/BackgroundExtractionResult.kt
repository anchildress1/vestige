package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.ResolvedExtraction
import dev.anchildress1.vestige.model.TemplateLabel

/**
 * One background pass per entry. [Success] carries the resolver's output plus at least one lens
 * that parsed; [Failed] means every lens exhausted its retry budget (or the resolver itself
 * threw); [TimedOut] means the run-level cap fired before lenses + resolver completed.
 * `modelCallCount` is diagnostic only — it is not the persisted `attempt_count`.
 */
sealed interface BackgroundExtractionResult {
    val totalElapsedMs: Long
    val lensResults: List<LensResult>
    val modelCallCount: Int

    data class Success(
        override val totalElapsedMs: Long,
        override val lensResults: List<LensResult>,
        override val modelCallCount: Int,
        val resolved: ResolvedExtraction,
        val templateLabel: TemplateLabel,
    ) : BackgroundExtractionResult

    data class Failed(
        override val totalElapsedMs: Long,
        override val lensResults: List<LensResult>,
        override val modelCallCount: Int,
        val lastError: String,
    ) : BackgroundExtractionResult

    /** Caller-supplied [timeoutMs] elapsed before the lens loop + resolver finished. */
    data class TimedOut(
        override val totalElapsedMs: Long,
        override val lensResults: List<LensResult>,
        override val modelCallCount: Int,
        val timeoutMs: Long,
    ) : BackgroundExtractionResult
}
