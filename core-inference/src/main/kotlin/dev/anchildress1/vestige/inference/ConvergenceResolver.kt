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
 * majority; other fields use a per-key equality predicate (identity-tuple match for
 * `stated_commitment`, structural equality otherwise).
 *
 * Lens parse failures are honored by the caller — a missing lens is treated as no opinion. With
 * only one surviving lens the entry is under-evidenced; every field (populated or not) resolves
 * to AMBIGUOUS rather than minting candidates from a single witness. With two surviving the
 * threshold collapses to "both must agree." Semantic no-op values (`null`, `false`, blank text,
 * empty lists/maps) do not count as corroborating evidence.
 */
// One resolver helper per ResolvedField; bound by the schema, not arbitrary surface.
@Suppress("TooManyFunctions")
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
            val raw = byLens[lens]?.fields?.get(key) ?: return@mapNotNull null
            meaningfulValue(raw)?.let { lens to raw }
        }
        return when {
            key == TAGS_KEY -> resolveTags(byLens, skepticalFlags)

            key == STATED_COMMITMENT_KEY -> resolveCommitment(byLens, matchingFlags)

            // Two of three lenses parse-failed: per ADR-002 §"Edge case — lens errors mid-call",
            // the surviving lens lacks corroboration, so every populated field is ambiguous.
            byLens.size == MIN_SURVIVING_LENSES_FOR_AMBIGUOUS -> ambiguousField(matchingFlags)

            key == VOCABULARY_KEY -> resolveVocabulary(byLens, matchingFlags)

            populated.isEmpty() -> ambiguousField(matchingFlags)

            populated.size == 1 &&
                populated.single().first == Lens.SKEPTICAL &&
                matchingFlags.isNotEmpty() -> ambiguousField(matchingFlags)

            populated.size == 1 -> ResolvedField(
                value = populated.single().second,
                verdict = ConfidenceVerdict.CANDIDATE,
                flags = matchingFlags,
                sourceLens = populated.single().first,
            )

            else -> resolveMultiple(populated, matchingFlags)
        }
    }

    private fun resolveCommitment(byLens: Map<Lens, LensExtraction>, matchingFlags: List<String>): ResolvedField {
        val populated = Lens.entries.mapNotNull { lens ->
            val raw =
                byLens[lens]?.fields?.get(STATED_COMMITMENT_KEY) as? Map<*, *>
                    ?: return@mapNotNull null
            lens.takeIf { meaningfulValue(raw) != null }?.let { it to raw }
        }
        return when {
            byLens.size == MIN_SURVIVING_LENSES_FOR_AMBIGUOUS -> ambiguousField(matchingFlags)

            populated.isEmpty() -> ambiguousField(matchingFlags)

            populated.size == 1 &&
                populated.single().first == Lens.SKEPTICAL &&
                matchingFlags.isNotEmpty() -> ambiguousField(matchingFlags)

            populated.size == 1 -> ResolvedField(
                value = populated.single().second,
                verdict = ConfidenceVerdict.CANDIDATE,
                flags = matchingFlags,
                sourceLens = populated.single().first,
            )

            else -> {
                val normalized = populated.map { (lens, map) ->
                    lens to map.toCommitmentIdentity()
                }
                val entryIds = normalized
                    .mapNotNull { (_, identity) -> identity.entryId.takeIf { identity.hasEntryId } }
                    .distinct()
                val nonNullTopics = normalized
                    .mapNotNull { (_, identity) -> identity.topicOrPerson }
                    .distinct()
                when {
                    entryIds.size > 1 -> disagreementField(matchingFlags)

                    nonNullTopics.size > 1 -> disagreementField(matchingFlags)

                    else -> canonicalCommitmentField(
                        representative = populated.first().second,
                        nonNullTopics = nonNullTopics,
                        entryIds = entryIds,
                        matchingFlags = matchingFlags,
                    )
                }
            }
        }
    }

    /**
     * Tone is an inferential read, not a vote: the Inferential lens wins the [VOCABULARY_KEY] word
     * outright. Literal/Skeptical can only corroborate (raising the verdict to CANONICAL when they
     * agree) — they never override Inferential's word. Falls back to whichever lens did name a tone
     * when Inferential abstained, and only AMBIGUOUS when no lens named one at all.
     */
    private fun resolveVocabulary(byLens: Map<Lens, LensExtraction>, matchingFlags: List<String>): ResolvedField {
        val words: List<Pair<Lens, String>> = Lens.entries.mapNotNull { lens ->
            (byLens[lens]?.fields?.get(VOCABULARY_KEY) as? String)
                ?.trim()?.lowercase()?.takeIf(String::isNotBlank)
                ?.let { lens to it }
        }
        val chosen = words.firstOrNull { it.first == Lens.INFERENTIAL }
            ?: words.firstOrNull()
            ?: return ambiguousField(matchingFlags)
        // No Skeptical flag kind binds to vocabulary (SkepticalFlagKinds.SCHEMA_BINDING), so there
        // is no CANONICAL_WITH_CONFLICT path here — corroboration is the only lift on the verdict.
        val agreement = words.count { it.second == chosen.second }
        val verdict = if (agreement >= MAJORITY_THRESHOLD) ConfidenceVerdict.CANONICAL else ConfidenceVerdict.CANDIDATE
        return ResolvedField(value = chosen.second, verdict = verdict, flags = matchingFlags, sourceLens = chosen.first)
    }

    private fun resolveMultiple(populated: List<Pair<Lens, Any>>, matchingFlags: List<String>): ResolvedField {
        val groups: Map<Any, List<Any>> = populated.groupBy(
            keySelector = { it.second },
            valueTransform = { it.second },
        )
        val majority = groups.entries.firstOrNull { it.value.size >= MAJORITY_THRESHOLD }
        return if (majority != null) {
            val majorityValue = majority.value.first()
            val verdict =
                if (matchingFlags.isEmpty()) ConfidenceVerdict.CANONICAL else ConfidenceVerdict.CANONICAL_WITH_CONFLICT
            ResolvedField(value = majorityValue, verdict = verdict, flags = matchingFlags)
        } else {
            ResolvedField(
                value = null,
                verdict = ConfidenceVerdict.AMBIGUOUS,
                flags = listOf(LENS_DISAGREEMENT_FLAG) + matchingFlags,
            )
        }
    }

    private fun disagreementField(matchingFlags: List<String>) = ResolvedField(
        value = null,
        verdict = ConfidenceVerdict.AMBIGUOUS,
        flags = listOf(LENS_DISAGREEMENT_FLAG) + matchingFlags,
    )

    private fun canonicalCommitmentField(
        representative: Map<*, *>,
        nonNullTopics: List<String>,
        entryIds: List<Any>,
        matchingFlags: List<String>,
    ): ResolvedField {
        val patched = enrichCommitmentRepresentative(
            representative = representative,
            agreedTopic = nonNullTopics.singleOrNull(),
            agreedEntryId = entryIds.singleOrNull(),
        )
        val verdict = if (matchingFlags.isEmpty()) {
            ConfidenceVerdict.CANONICAL
        } else {
            ConfidenceVerdict.CANONICAL_WITH_CONFLICT
        }
        return ResolvedField(value = patched, verdict = verdict, flags = matchingFlags)
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
                value = null,
                verdict = ConfidenceVerdict.AMBIGUOUS,
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
        // Walk lens order once and persist the first-seen surface form per stem. Stems are a
        // counting aid only — never the saved value — because the singularizer is naive and would
        // corrupt legitimate singular tags ending in `s` / `ies` (e.g. `news` → `new`,
        // `series` → `sery`).
        val orderedConsensus = mutableListOf<String>()
        val claimedStems = hashSetOf<String>()
        for ((_, tags) in populated) {
            for (tag in tags) {
                val stem = stemForCount(tag)
                if (stem in canonicalStems && claimedStems.add(stem)) orderedConsensus.add(tag)
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
                    value = listOf(fallback),
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
        val lower = tag.lowercase().replace("-", "")
        if (lower.length <= MIN_STEM_LENGTH) return lower
        return when {
            lower.endsWith(IES_SUFFIX) -> lower.dropLast(IES_SUFFIX.length) + "y"
            lower.endsWith("ss") || lower.endsWith("us") || lower.endsWith("is") -> lower
            lower.endsWith('s') -> lower.dropLast(1)
            else -> lower
        }
    }

    private fun meaningfulValue(value: Any?): Any? = when (value) {
        null -> null
        is String -> value.takeIf(String::isNotBlank)
        is Boolean -> value.takeIf { it }
        is List<*> -> value.takeIf { values -> values.any { meaningfulValue(it) != null } }
        is Map<*, *> -> value.takeIf { values -> values.values.any { meaningfulValue(it) != null } }
        else -> value
    }

    private fun Map<*, *>.toCommitmentIdentity(): CommitmentIdentity {
        val entryId = this[ENTRY_ID_KEY]
        return CommitmentIdentity(
            topicOrPerson = (this[TOPIC_OR_PERSON_KEY] as? String)?.trim()?.takeIf(String::isNotBlank)?.lowercase(),
            entryId = (entryId as? String)?.trim()?.takeIf(String::isNotBlank) ?: entryId,
            hasEntryId = containsKey(ENTRY_ID_KEY),
        )
    }

    private fun enrichCommitmentRepresentative(
        representative: Map<*, *>,
        agreedTopic: String?,
        agreedEntryId: Any?,
    ): Map<String, Any?> {
        val normalized = representative.entries.associate { (key, value) -> key.toString() to value }
            .toMutableMap()
        if (normalized[TOPIC_OR_PERSON_KEY] !is String || normalized[TOPIC_OR_PERSON_KEY].toString().isBlank()) {
            normalized[TOPIC_OR_PERSON_KEY] = agreedTopic
        }
        if (!normalized.containsKey(ENTRY_ID_KEY) && agreedEntryId != null) {
            normalized[ENTRY_ID_KEY] = agreedEntryId
        }
        return normalized
    }

    private fun ambiguousField(flags: List<String>): ResolvedField =
        ResolvedField(value = null, verdict = ConfidenceVerdict.AMBIGUOUS, flags = flags)

    private fun flagBelongsToField(flag: String, field: String): Boolean =
        FLAG_KIND_TO_FIELD[flag.substringBefore(':')] == field

    private fun LensExtraction.tagsOrNull(): List<String>? =
        (fields[TAGS_KEY] as? List<*>)?.mapNotNull { it as? String }?.takeIf { it.isNotEmpty() }

    private companion object {
        const val TAGS_KEY = "tags"
        const val STATED_COMMITMENT_KEY = "stated_commitment"
        const val VOCABULARY_KEY = "vocabulary"
        const val TOPIC_OR_PERSON_KEY = "topic_or_person"
        const val ENTRY_ID_KEY = "entry_id"
        const val LENS_DISAGREEMENT_FLAG = "lens-disagreement"
        const val MAJORITY_THRESHOLD = 2
        const val MIN_SURVIVING_LENSES_FOR_AMBIGUOUS = 1
        const val MIN_STEM_LENGTH = 3
        const val IES_SUFFIX = "ies"

        /**
         * Schema-binding Skeptical flag kinds → the field each annotates. Sourced from
         * [SkepticalFlagKinds] so the STT-D divergence harness, the resolver, and any future
         * Reading-view code share one definition of "this flag binds to a stored field."
         */
        val FLAG_KIND_TO_FIELD: Map<String, String> = SkepticalFlagKinds.SCHEMA_BINDING
    }
}

private data class CommitmentIdentity(val topicOrPerson: String?, val entryId: Any?, val hasEntryId: Boolean)
