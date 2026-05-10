package dev.anchildress1.vestige.model

/**
 * One lens × five surfaces. [fields] holds the populated field/value pairs; null means the lens
 * chose not to populate. [flags] is Skeptical-only — used for conflict markers.
 */
data class LensExtraction(val lens: Lens, val fields: Map<String, Any?>, val flags: List<String> = emptyList())

/** One field's convergence outcome. `value` is null when [verdict] is `AMBIGUOUS`. */
data class ResolvedField(val value: Any?, val verdict: ConfidenceVerdict, val flags: List<String> = emptyList())

data class ResolvedExtraction(val fields: Map<String, ResolvedField>)
