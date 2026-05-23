package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.PatternKind
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Canonical signature serialization per ADR-003 §"`pattern_id` generation". Tags + labels are
 * lowercased + kebab-cased + sorted before hashing; tokens are stemmed and lowercased. Stability
 * across re-detection runs is the load-bearing property — adding a supporting entry must never
 * change the hash, and surface-form drift between separator conventions must collapse to one id.
 *
 * The serialized form is hand-built rather than going through `org.json.JSONObject`: that class
 * uses `HashMap` on the upstream `org.json:json` artifact and `LinkedHashMap` on Android, so
 * key-iteration order can differ across runtimes. A divergence between unit-test and on-device
 * serialization would produce different SHA-256 hashes for the same logical signature and
 * silently break the content-addressable contract that ADR-002's `recurrence_link` predicate
 * depends on. All inputs are constrained to `[a-z0-9-]` by [TagNormalize.kebab] (labels, tags,
 * commitment topics) or lowercased + [TokenStemmer.stem]-folded (vocab tone words), so JSON
 * string-escaping isn't required.
 */
internal object PatternSignature {

    fun forTemplateRecurrence(label: String): Signature {
        val canonical = TagNormalize.kebab(label)
        val json = """{"kind":"${PatternKind.TEMPLATE_RECURRENCE.serial}","label":"$canonical"}"""
        return Signature.of(PatternKind.TEMPLATE_RECURRENCE, json, canonical)
    }

    fun forTagPair(label: String, tags: Set<String>): Signature {
        require(tags.size == 2) { "tag pair signature requires exactly 2 tags, got ${tags.size}" }
        val canonicalLabel = TagNormalize.kebab(label)
        val canonicalTags = tags.map(TagNormalize::kebab).sorted()
        val kind = PatternKind.TAG_PAIR_CO_OCCURRENCE.serial
        val tagsJson = """["${canonicalTags[0]}","${canonicalTags[1]}"]"""
        val json = """{"kind":"$kind","label":"$canonicalLabel","tags":$tagsJson}"""
        return Signature.of(PatternKind.TAG_PAIR_CO_OCCURRENCE, json, canonicalLabel)
    }

    fun forGoblinHours(): Signature {
        val json = """{"kind":"${PatternKind.TIME_OF_DAY_CLUSTER.serial}","bucket":"goblin"}"""
        return Signature.of(PatternKind.TIME_OF_DAY_CLUSTER, json, null)
    }

    fun forCommitment(topicOrPerson: String): Signature {
        val canonical = TagNormalize.kebab(topicOrPerson)
        val json = """{"kind":"${PatternKind.COMMITMENT_RECURRENCE.serial}","topic_or_person":"$canonical"}"""
        return Signature.of(PatternKind.COMMITMENT_RECURRENCE, json, null)
    }

    /**
     * Stems the dominant `vocabularyWord` of a vocab cluster. [PatternMatcher.matchesVocab]
     * canonicalizes each entry's tone word the same way before comparing to this signature, so a
     * `tireds` tone word still hits a pattern minted from `tired`.
     */
    fun forVocabToken(token: String): Signature {
        val canonical = canonicalVocabToken(token)
        val json = """{"kind":"${PatternKind.VOCAB_FREQUENCY.serial}","token":"$canonical"}"""
        return Signature.of(PatternKind.VOCAB_FREQUENCY, json, null)
    }

    /**
     * Canonical form of a model-emitted tone word: kebab-folded to `[a-z0-9-]` (the tone word is
     * free-form, so this both keeps the hand-built signature JSON escape-free and gives the
     * matcher a stable compare key) then plural-folded via [TokenStemmer]. The shared chokepoint
     * for detection identity and matching so the two cannot drift.
     */
    fun canonicalVocabToken(word: String): String = TokenStemmer.stem(TagNormalize.kebab(word))

    fun forWeekdayTimeBlock(dayOfWeek: String, timeBlock: String): Signature {
        val canonicalDay = TagNormalize.kebab(dayOfWeek)
        val canonicalBlock = TemporalPatternRules.canonicalTimeBlock(timeBlock)
        val kind = PatternKind.TEMPORAL_RELATIVE.serial
        val relation = TemporalRelation.WEEKDAY_TIME_BLOCK.serial
        val json = "{\"kind\":\"$kind\",\"relation\":\"$relation\"," +
            "\"day_of_week\":\"$canonicalDay\",\"time_block\":\"$canonicalBlock\"}"
        return Signature.of(PatternKind.TEMPORAL_RELATIVE, json, null)
    }

    fun forMonthStart(): Signature {
        val kind = PatternKind.TEMPORAL_RELATIVE.serial
        val relation = TemporalRelation.MONTH_START.serial
        val day = TemporalPatternRules.MONTH_START_DAY
        val json = """{"kind":"$kind","relation":"$relation","day_of_month":$day}"""
        return Signature.of(PatternKind.TEMPORAL_RELATIVE, json, null)
    }
}

/**
 * Construct only via [Signature.of] — [patternId] is `sha256(json)` by construction, so the
 * content-addressable contract can't be broken by a hand-built mismatched pair.
 */
@ConsistentCopyVisibility
internal data class Signature private constructor(
    val kind: PatternKind,
    val json: String,
    val patternId: String,
    val templateLabel: String?,
) {
    companion object {
        fun of(kind: PatternKind, json: String, templateLabel: String?): Signature =
            Signature(kind, json, sha256(json), templateLabel)

        private fun sha256(payload: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            return HexFormat.of().formatHex(digest)
        }
    }
}
