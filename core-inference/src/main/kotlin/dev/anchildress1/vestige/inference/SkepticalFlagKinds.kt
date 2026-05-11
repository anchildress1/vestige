package dev.anchildress1.vestige.inference

/** Canonical Skeptical-lens flag-kind registry. Source: `resources/lenses/skeptical.txt`. */
object SkepticalFlagKinds {

    /** Schema-binding kind → field it annotates. Entry-level kinds (`time-inconsistency`, `other`) are absent here. */
    val SCHEMA_BINDING: Map<String, String> = mapOf(
        "vocabulary-contradiction" to "energy_descriptor",
        "state-behavior-mismatch" to "energy_descriptor",
        "commitment-without-anchor" to "stated_commitment",
        "unsupported-recurrence" to "recurrence_link",
    )

    val SCHEMA_BINDING_KINDS: Set<String> = SCHEMA_BINDING.keys

    fun isSchemaBinding(flag: String): Boolean = flag.substringBefore(':') in SCHEMA_BINDING_KINDS
}
