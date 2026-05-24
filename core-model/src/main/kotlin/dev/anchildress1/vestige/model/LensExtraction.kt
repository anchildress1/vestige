package dev.anchildress1.vestige.model

/**
 * One lens × five surfaces. [fields] holds the populated field/value pairs; null means the lens
 * chose not to populate. [flags] is Skeptical-only — used for conflict markers.
 */
data class LensExtraction(val lens: Lens, val fields: Map<String, Any?>, val flags: List<String> = emptyList())

/**
 * One field's convergence outcome. `value` is always null when [verdict] is `AMBIGUOUS`, and may
 * also be null on `CONSENSUS` for nullable schema fields (e.g. all three lenses agreed the field
 * has no value). [sourceLens] records the lens a single-witness value came from: every `CANDIDATE`
 * (per ADR-002 §"Resolution rules" rule 2 — recorded so the pattern engine can promote candidates by
 * source later), plus the vocabulary path, which keeps the contributing lens even at `CONSENSUS`. It
 * is `null` on the multi-lens `CONSENSUS` paths (tags, commitment), where no single lens owns the value.
 */
data class ResolvedField(
    val value: Any?,
    val verdict: ConfidenceVerdict,
    val flags: List<String> = emptyList(),
    val sourceLens: Lens? = null,
)

data class ResolvedExtraction(val fields: Map<String, ResolvedField>)
