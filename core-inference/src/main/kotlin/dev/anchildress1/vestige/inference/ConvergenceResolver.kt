package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.ConfidenceVerdict
import dev.anchildress1.vestige.model.Lens
import dev.anchildress1.vestige.model.LensExtraction
import dev.anchildress1.vestige.model.ResolvedExtraction
import dev.anchildress1.vestige.model.ResolvedField

/**
 * Reduces 0–3 surviving [LensExtraction]s to one [ResolvedExtraction] per the convergence rules:
 * ≥2 lenses agree → CANONICAL; only one lens populates → CANDIDATE; lenses disagree →
 * AMBIGUOUS (null value, noted); Skeptical flags conflict even on agreement →
 * CANONICAL_WITH_CONFLICT.
 */
fun interface ConvergenceResolver {
    fun resolve(extractions: List<LensExtraction>): ResolvedExtraction
}

/**
 * Pure data merge. Iterates the union of field keys across the supplied lenses and applies the
 * four resolution rules per ADR-002 §"Convergence Resolver Contract". `tags` use a per-tag
 * ≥2-of-3 majority count with a Literal-strongest fallback (CANDIDATE) when no tag reaches
 * majority; other fields use a per-key equality predicate (case-insensitive trim for
 * `energy_descriptor`, identity-tuple match for `stated_commitment`, structural equality
 * otherwise).
 *
 * Lens parse failures are honored by the caller — a missing lens is treated as no opinion. With
 * only one surviving lens the entry is under-evidenced; every field (populated or not) resolves
 * to AMBIGUOUS rather than minting candidates from a single witness. With two surviving the
 * threshold collapses to "both must agree."
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
        val matchingFlags = skepticalFlags.filter { flagBelongsToField(it, key) }
        val populated: List<Pair<Lens, Any>> = Lens.entries.mapNotNull { lens ->
            byLens[lens]?.fields?.get(key)?.let { lens to it }
        }
        return when {
            key == TAGS_KEY -> resolveTags(byLens, skepticalFlags)

            // Two of three lenses parse-failed: per ADR-002 §"Edge case — lens errors mid-call",
            // the surviving lens lacks corroboration, so every populated field is ambiguous.
            byLens.size == MIN_SURVIVING_LENSES_FOR_AMBIGUOUS -> ResolvedField(
                value = null,
                verdict = ConfidenceVerdict.AMBIGUOUS,
                flags = matchingFlags,
            )

            populated.isEmpty() ->
                ResolvedField(value = null, verdict = ConfidenceVerdict.CANONICAL, flags = matchingFlags)

            populated.size == 1 -> ResolvedField(
                value = populated.single().second,
                verdict = ConfidenceVerdict.CANDIDATE,
                flags = matchingFlags,
                sourceLens = populated.single().first,
            )

            else -> resolveMultiple(key, populated, matchingFlags)
        }
    }

    private fun resolveMultiple(
        key: String,
        populated: List<Pair<Lens, Any>>,
        matchingFlags: List<String>,
    ): ResolvedField {
        val groups: Map<Any, List<Any>> = populated.groupBy(
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
        if (byLens.size == MIN_SURVIVING_LENSES_FOR_AMBIGUOUS) {
            return ResolvedField(
                value = null,
                verdict = ConfidenceVerdict.AMBIGUOUS,
                flags = matchingFlags,
            )
        }

        val populated: List<Pair<Lens, List<String>>> = Lens.entries.mapNotNull { lens ->
            val raw = byLens[lens]?.fields?.get(TAGS_KEY) ?: return@mapNotNull null
            val tags = (raw as? List<*>)?.mapNotNull { it as? String }?.distinct().orEmpty()
            lens.takeIf { tags.isNotEmpty() }?.let { it to tags }
        }
        // ADR-002 §"Per-field agreement" — normalize plurals for majority counting; keep the
        // original surface form for the saved value. Two lenses emitting "meeting" / "meetings"
        // count as the same tag.
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
        // Count by plural-stripped stem so "meeting" / "meetings" converge.
        val canonicalStems: Set<String> = populated
            .flatMap { (_, tags) -> tags.map(::stemForCount).distinct() }
            .groupingBy { it }
            .eachCount()
            .filterValues { it >= MAJORITY_THRESHOLD }
            .keys
        // Walk lens order once so the saved list stays stable, but persist the normalized stem
        // rather than whichever plural surface form happened to appear first.
        val orderedConsensus = mutableListOf<String>()
        val claimedStems = hashSetOf<String>()
        for ((_, tags) in populated) {
            for (tag in tags) {
                val stem = stemForCount(tag)
                if (stem in canonicalStems && claimedStems.add(stem)) orderedConsensus.add(stem)
            }
        }
        return if (orderedConsensus.isNotEmpty()) {
            val verdict =
                if (matchingFlags.isEmpty()) ConfidenceVerdict.CANONICAL else ConfidenceVerdict.CANONICAL_WITH_CONFLICT
            ResolvedField(value = orderedConsensus, verdict = verdict, flags = matchingFlags)
        } else {
            // No tag reaches majority — surface Literal's strongest tag as a candidate so the P0
            // floor ("at least one visible tag") survives sparse entries.
            val fallback = byLens[Lens.LITERAL]?.tagsOrNull()?.firstOrNull()
            if (fallback != null) {
                ResolvedField(
                    value = listOf(stemForCount(fallback)),
                    verdict = ConfidenceVerdict.CANDIDATE,
                    flags = matchingFlags,
                    sourceLens = Lens.LITERAL,
                )
            } else {
                ResolvedField(
                    value = null,
                    verdict = ConfidenceVerdict.AMBIGUOUS,
                    flags = listOf(LENS_DISAGREEMENT_FLAG) + matchingFlags,
                )
            }
        }
    }

    /**
     * Lightweight singularizer: drops a trailing `s` when the word is long enough and the suffix
     * is a regular plural (not `ss` / `us` / `is` which are usually singular endings). Adequate
     * for v1 tag domains (people, topics, activities). Irregular plurals are not handled —
     * tightening lands when STT-C surfaces real flakiness.
     */
    private fun stemForCount(tag: String): String {
        val lower = tag.lowercase()
        if (lower.length <= MIN_STEM_LENGTH) return lower
        return when {
            lower.endsWith(IES_SUFFIX) -> lower.dropLast(IES_SUFFIX.length) + "y"
            lower.endsWith("ss") || lower.endsWith("us") || lower.endsWith("is") -> lower
            lower.endsWith('s') -> lower.dropLast(1)
            else -> lower
        }
    }

    private fun canonicalize(key: String, value: Any): Any = when (key) {
        ENERGY_DESCRIPTOR_KEY -> (value as? String)?.trim()?.lowercase() ?: value
        STATED_COMMITMENT_KEY -> canonicalizeCommitment(value)
        else -> value
    }

    private fun canonicalizeCommitment(value: Any): Any {
        // ADR-002 §"Per-field agreement" — commitment agreement is "same topic_or_person AND same
        // entry_id reference." Both keys are nullable in the lens output (entry_id is injected by
        // storage post-resolver; topic_or_person can be null per `lenses/output-schema.txt`), so
        // any commitment Map produces a CommitmentIdentity. `hasEntryId` distinguishes "entry_id
        // not yet assigned" from "entry_id explicitly null" — without it two lenses tagged with
        // different storage entry_ids would still converge as null-vs-null.
        val commitment = value as? Map<*, *> ?: return value
        val entryId = commitment[ENTRY_ID_KEY]
        return CommitmentIdentity(
            topicOrPerson = commitment[TOPIC_OR_PERSON_KEY],
            entryId = entryId,
            hasEntryId = commitment.containsKey(ENTRY_ID_KEY),
        )
    }

    private fun flagBelongsToField(flag: String, field: String): Boolean =
        FLAG_KIND_TO_FIELD[flag.substringBefore(':')] == field

    private fun LensExtraction.tagsOrNull(): List<String>? =
        (fields[TAGS_KEY] as? List<*>)?.mapNotNull { it as? String }?.takeIf { it.isNotEmpty() }

    private companion object {
        const val TAGS_KEY = "tags"
        const val ENERGY_DESCRIPTOR_KEY = "energy_descriptor"
        const val STATED_COMMITMENT_KEY = "stated_commitment"
        const val TOPIC_OR_PERSON_KEY = "topic_or_person"
        const val ENTRY_ID_KEY = "entry_id"
        const val LENS_DISAGREEMENT_FLAG = "lens-disagreement"
        const val MAJORITY_THRESHOLD = 2
        const val MIN_SURVIVING_LENSES_FOR_AMBIGUOUS = 1
        const val MIN_STEM_LENGTH = 3
        const val IES_SUFFIX = "ies"

        /**
         * Skeptical flag `kind` (per `core-inference/.../resources/lenses/skeptical.txt`) → schema
         * field the flag annotates. `time-inconsistency` and `other` are entry-level concerns
         * with no specific field binding; they ride the entry's persisted `LensResult.flags` and
         * surface in Phase 4's Reading view rather than flipping any field's verdict.
         */
        val FLAG_KIND_TO_FIELD: Map<String, String> = mapOf(
            "vocabulary-contradiction" to ENERGY_DESCRIPTOR_KEY,
            "state-behavior-mismatch" to ENERGY_DESCRIPTOR_KEY,
            "commitment-without-anchor" to STATED_COMMITMENT_KEY,
            "unsupported-recurrence" to "recurrence_link",
        )
    }
}

private data class CommitmentIdentity(val topicOrPerson: Any?, val entryId: Any?, val hasEntryId: Boolean)
