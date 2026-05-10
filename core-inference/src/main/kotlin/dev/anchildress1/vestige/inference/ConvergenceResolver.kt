package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.ConfidenceVerdict
import dev.anchildress1.vestige.model.Lens
import dev.anchildress1.vestige.model.LensExtraction
import dev.anchildress1.vestige.model.ResolvedExtraction
import dev.anchildress1.vestige.model.ResolvedField

/**
 * Reduces three [LensExtraction]s to one [ResolvedExtraction] per the convergence rules:
 * ≥2 lenses agree → CANONICAL; only one lens populates → CANDIDATE; lenses disagree →
 * AMBIGUOUS (null value, noted); Skeptical flags conflict even on agreement →
 * CANONICAL_WITH_CONFLICT.
 */
interface ConvergenceResolver {
    fun resolve(extractions: List<LensExtraction>): ResolvedExtraction
}

/**
 * Pure data merge. Iterates the union of field keys across the supplied lenses and applies the
 * four resolution rules per ADR-002 §"Convergence Resolver Contract". `tags` follow the
 * list-intersection rule (per-tag count across lenses); other fields use a per-key equality
 * predicate (case-insensitive for `energy_descriptor`, structural equality otherwise).
 *
 * Lens parse failures are honored by the caller — a missing lens is treated as no opinion. With
 * one surviving lens every populated field becomes CANDIDATE; with two surviving the threshold
 * collapses to "both must agree."
 */
class DefaultConvergenceResolver : ConvergenceResolver {

    override fun resolve(extractions: List<LensExtraction>): ResolvedExtraction {
        val byLens: Map<Lens, LensExtraction> = extractions.associateBy(LensExtraction::lens)
        val skepticalFlags: List<String> = byLens[Lens.SKEPTICAL]?.flags.orEmpty()
        val keys: Set<String> = extractions.flatMapTo(linkedSetOf()) { it.fields.keys }
        val resolved = keys.associateWith { key -> resolveField(key, byLens, skepticalFlags) }
        return ResolvedExtraction(fields = resolved)
    }

    private fun resolveField(
        key: String,
        byLens: Map<Lens, LensExtraction>,
        skepticalFlags: List<String>,
    ): ResolvedField {
        if (key == TAGS_KEY) return resolveTags(byLens, skepticalFlags)

        val populated: List<Pair<Lens, Any>> = Lens.entries.mapNotNull { lens ->
            byLens[lens]?.fields?.get(key)?.let { lens to it }
        }
        val matchingFlags = skepticalFlags.filter { flagBelongsToField(it, key) }

        return when (populated.size) {
            0 -> ResolvedField(value = null, verdict = ConfidenceVerdict.CANONICAL, flags = matchingFlags)

            1 -> ResolvedField(
                value = populated.single().second,
                verdict = ConfidenceVerdict.CANDIDATE,
                flags = matchingFlags,
            )

            else -> resolveMultiple(key, populated, matchingFlags)
        }
    }

    private fun resolveMultiple(
        key: String,
        populated: List<Pair<Lens, Any>>,
        matchingFlags: List<String>,
    ): ResolvedField {
        val groups: Map<Any?, List<Any>> = populated.groupBy(
            keySelector = { canonicalize(key, it.second) },
            valueTransform = { it.second },
        )
        val majority = groups.entries.firstOrNull { it.value.size >= MAJORITY_THRESHOLD }
        return if (majority != null) {
            val verdict =
                if (matchingFlags.isEmpty()) ConfidenceVerdict.CANONICAL else ConfidenceVerdict.CANONICAL_WITH_CONFLICT
            ResolvedField(value = majority.value.first(), verdict = verdict, flags = matchingFlags)
        } else {
            ResolvedField(
                value = null,
                verdict = ConfidenceVerdict.AMBIGUOUS,
                flags = listOf(LENS_DISAGREEMENT_FLAG) + matchingFlags,
            )
        }
    }

    private fun resolveTags(byLens: Map<Lens, LensExtraction>, skepticalFlags: List<String>): ResolvedField {
        val matchingFlags = skepticalFlags.filter { flagBelongsToField(it, TAGS_KEY) }
        val populated: List<Pair<Lens, List<String>>> = Lens.entries.mapNotNull { lens ->
            val raw = byLens[lens]?.fields?.get(TAGS_KEY) ?: return@mapNotNull null
            val tags = (raw as? List<*>)?.mapNotNull { it as? String }?.distinct().orEmpty()
            lens.takeIf { tags.isNotEmpty() }?.let { it to tags }
        }
        return when {
            populated.isEmpty() -> ResolvedField(
                value = emptyList<String>(),
                verdict = ConfidenceVerdict.CANONICAL,
                flags = matchingFlags,
            )

            else -> resolvePopulatedTags(byLens, populated, matchingFlags)
        }
    }

    private fun resolvePopulatedTags(
        byLens: Map<Lens, LensExtraction>,
        populated: List<Pair<Lens, List<String>>>,
        matchingFlags: List<String>,
    ): ResolvedField {
        val canonical: Set<String> = populated
            .flatMap { it.second }
            .groupingBy { it }
            .eachCount()
            .filterValues { it >= MAJORITY_THRESHOLD }
            .keys
        return if (canonical.isNotEmpty()) {
            val verdict =
                if (matchingFlags.isEmpty()) ConfidenceVerdict.CANONICAL else ConfidenceVerdict.CANONICAL_WITH_CONFLICT
            ResolvedField(
                value = orderedConsensusTags(populated, canonical),
                verdict = verdict,
                flags = matchingFlags,
            )
        } else {
            // No tag reaches majority — surface Literal's strongest tag as a candidate so the P0
            // floor ("at least one visible tag") survives sparse entries.
            val fallback = byLens[Lens.LITERAL]?.tagsOrNull()?.firstOrNull()
                ?: populated.first().second.first()
            ResolvedField(value = listOf(fallback), verdict = ConfidenceVerdict.CANDIDATE, flags = matchingFlags)
        }
    }

    private fun orderedConsensusTags(populated: List<Pair<Lens, List<String>>>, canonical: Set<String>): List<String> {
        val seen = linkedSetOf<String>()
        for ((_, tags) in populated) {
            for (tag in tags) if (tag in canonical) seen.add(tag)
        }
        return seen.toList()
    }

    private fun canonicalize(key: String, value: Any): Any = when (key) {
        ENERGY_DESCRIPTOR_KEY -> (value as? String)?.trim()?.lowercase() ?: value
        else -> value
    }

    private fun flagBelongsToField(flag: String, field: String): Boolean = flag.substringBefore(':') == field

    private fun LensExtraction.tagsOrNull(): List<String>? =
        (fields[TAGS_KEY] as? List<*>)?.mapNotNull { it as? String }?.takeIf { it.isNotEmpty() }

    private companion object {
        const val TAGS_KEY = "tags"
        const val ENERGY_DESCRIPTOR_KEY = "energy_descriptor"
        const val LENS_DISAGREEMENT_FLAG = "lens-disagreement"
        const val MAJORITY_THRESHOLD = 2
    }
}
