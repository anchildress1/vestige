package dev.anchildress1.vestige.model

/**
 * One lens × five surfaces. [fields] holds the populated field/value pairs; null means the lens
 * chose not to populate. [flags] is Skeptical-only — used for conflict markers.
 */
data class LensExtraction(val lens: Lens, val fields: Map<String, Any?>, val flags: List<String> = emptyList())

/**
 * One field's convergence outcome. `value` is always null when [verdict] is `AMBIGUOUS`, and may
 * also be null on `CANONICAL` for nullable schema fields (e.g. all three lenses agreed the field
 * has no value). [sourceLens] is populated only when [verdict] is `CANDIDATE` (single lens
 * contributed the value, per ADR-002 §"Resolution rules" rule 2 — recorded so the pattern engine
 * can promote candidates by source later); `null` for every other verdict.
 */
data class ResolvedField(
    val value: Any?,
    val verdict: ConfidenceVerdict,
    val flags: List<String> = emptyList(),
    val sourceLens: Lens? = null,
)

data class ResolvedExtraction(val fields: Map<String, ResolvedField>)
