package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.PatternKind
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale

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
 * commitment topics) or by [TokenStemmer.stem] + `WORD_SPLIT` in [PatternDetector] (vocab
 * tokens), so JSON string-escaping isn't required.
 */
internal object PatternSignature {

    fun forTemplateRecurrence(label: String): Signature {
        val canonical = TagNormalize.kebab(label)
        val json = """{"kind":"${PatternKind.TEMPLATE_RECURRENCE.serial}","label":"$canonical"}"""
        return Signature(PatternKind.TEMPLATE_RECURRENCE, json, sha256(json), canonical)
    }

    fun forTagPair(label: String, tags: Set<String>): Signature {
        require(tags.size == 2) { "tag pair signature requires exactly 2 tags, got ${tags.size}" }
        val canonicalLabel = TagNormalize.kebab(label)
        val canonicalTags = tags.map(TagNormalize::kebab).sorted()
        val kind = PatternKind.TAG_PAIR_CO_OCCURRENCE.serial
        val tagsJson = """["${canonicalTags[0]}","${canonicalTags[1]}"]"""
        val json = """{"kind":"$kind","label":"$canonicalLabel","tags":$tagsJson}"""
        return Signature(PatternKind.TAG_PAIR_CO_OCCURRENCE, json, sha256(json), canonicalLabel)
    }

    fun forGoblinHours(): Signature {
        val json = """{"kind":"${PatternKind.TIME_OF_DAY_CLUSTER.serial}","bucket":"goblin"}"""
        return Signature(PatternKind.TIME_OF_DAY_CLUSTER, json, sha256(json), null)
    }

    fun forCommitment(topicOrPerson: String): Signature {
        val canonical = TagNormalize.kebab(topicOrPerson)
        val json = """{"kind":"${PatternKind.COMMITMENT_RECURRENCE.serial}","topic_or_person":"$canonical"}"""
        return Signature(PatternKind.COMMITMENT_RECURRENCE, json, sha256(json), null)
    }

    /**
     * Defensive stemming: callers in [PatternDetector] already feed stemmed tokens, but
     * [PatternMatcher.matchesVocab] also stems entry tokens before comparing to the signature.
     * Applying [TokenStemmer.stem] here means an out-of-band caller passing `"meetings"` still
     * produces a signature that the matcher's stemmed entry tokens will hit.
     */
    fun forVocabToken(token: String): Signature {
        val canonical = TokenStemmer.stem(token.lowercase(Locale.ROOT))
        val json = """{"kind":"${PatternKind.VOCAB_FREQUENCY.serial}","token":"$canonical"}"""
        return Signature(PatternKind.VOCAB_FREQUENCY, json, sha256(json), null)
    }

    private fun sha256(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }
}

internal data class Signature(
    val kind: PatternKind,
    val json: String,
    val patternId: String,
    val templateLabel: String?,
)
