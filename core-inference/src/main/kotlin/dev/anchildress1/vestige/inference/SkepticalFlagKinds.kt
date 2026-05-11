package dev.anchildress1.vestige.inference

/**
 * Canonical Skeptical-lens flag-kind registry per `resources/lenses/skeptical.txt`.
 *
 * The Skeptical lens emits flags shaped as `<kind>:<snippet>:<note>` (collapsed at parse time in
 * [LensResponseParser]). Two classes of kind exist:
 *
 * - **Schema-binding kinds** map to a specific extraction-schema field. The convergence resolver
 *   uses them to flip a field's verdict from `CANONICAL` to `CANONICAL_WITH_CONFLICT`. The STT-D
 *   divergence harness counts them as meaningful signal because they are evidence of stored
 *   field-level disagreement.
 * - **Entry-level kinds** (`time-inconsistency`, `other`) describe the entry as a whole and do
 *   not bind to any single field. They ride the entry's persisted `LensResult.flags` and surface
 *   in Phase 4's Reading view, but they do not flip a field verdict and they do not count as
 *   STT-D divergence on their own.
 *
 * Both [DefaultConvergenceResolver] and the STT-D harness consume this registry so the
 * "schema-binding" definition has exactly one source of truth.
 */
object SkepticalFlagKinds {

    /** Each kind → the schema field its presence annotates. */
    val SCHEMA_BINDING: Map<String, String> = mapOf(
        "vocabulary-contradiction" to "energy_descriptor",
        "state-behavior-mismatch" to "energy_descriptor",
        "commitment-without-anchor" to "stated_commitment",
        "unsupported-recurrence" to "recurrence_link",
    )

    /** Schema-binding kind names only (no field mapping). */
    val SCHEMA_BINDING_KINDS: Set<String> = SCHEMA_BINDING.keys

    /**
     * `true` when [flag]'s `kind:` prefix is one of [SCHEMA_BINDING_KINDS]. Entry-level kinds
     * (`time-inconsistency`, `other`) and malformed flags return `false`.
     */
    fun isSchemaBinding(flag: String): Boolean = flag.substringBefore(':') in SCHEMA_BINDING_KINDS
}
