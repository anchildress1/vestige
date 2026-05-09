package dev.anchildress1.vestige.model

/**
 * One pass of the multi-lens pipeline: a single [Lens] applied to all five surfaces produces a
 * [LensExtraction]. [fields] carries the populated field name → value pairs (null values mean
 * the lens explicitly chose not to populate the field). [flags] is a free-form list the
 * Skeptical lens uses to surface conflict markers per `concept-locked.md` §"Convergence rules".
 */
data class LensExtraction(val lens: Lens, val fields: Map<String, Any?>, val flags: List<String> = emptyList())

/**
 * Convergence outcome for a single field after the resolver runs over [LensExtraction]s. The
 * `value` is `null` when the verdict is [ConfidenceVerdict.AMBIGUOUS]; the field is still saved
 * with a note so re-eval can revisit (`concept-locked.md` §"Convergence rules").
 */
data class ResolvedField(val value: Any?, val verdict: ConfidenceVerdict, val flags: List<String> = emptyList())

/** Output of the convergence resolver — one [ResolvedField] per field name across all lenses. */
data class ResolvedExtraction(val fields: Map<String, ResolvedField>)
