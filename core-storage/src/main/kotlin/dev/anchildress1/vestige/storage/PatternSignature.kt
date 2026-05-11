package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.PatternKind
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale

/**
 * Canonical signature serialization per ADR-003 §"`pattern_id` generation". Tags + labels are
 * lowercased + kebab-cased + sorted before hashing; tokens are stemmed and lowercased. Stability
 * across re-detection runs is the load-bearing property — adding a supporting entry must never
 * change the hash, and surface-form drift between separator conventions must collapse to one id.
 */
internal object PatternSignature {

    fun forTemplateRecurrence(label: String): Signature {
        val canonical = TagNormalize.kebab(label)
        val json = JSONObject()
            .put("kind", PatternKind.TEMPLATE_RECURRENCE.serial)
            .put("label", canonical)
            .toString()
        return Signature(PatternKind.TEMPLATE_RECURRENCE, json, sha256(json), canonical)
    }

    fun forTagPair(label: String, tags: Set<String>): Signature {
        require(tags.size == 2) { "tag pair signature requires exactly 2 tags, got ${tags.size}" }
        val canonicalLabel = TagNormalize.kebab(label)
        val canonicalTags = tags.map(TagNormalize::kebab).sorted()
        val json = JSONObject()
            .put("kind", PatternKind.TAG_PAIR_CO_OCCURRENCE.serial)
            .put("label", canonicalLabel)
            .put("tags", JSONArray().put(canonicalTags[0]).put(canonicalTags[1]))
            .toString()
        return Signature(PatternKind.TAG_PAIR_CO_OCCURRENCE, json, sha256(json), canonicalLabel)
    }

    fun forGoblinHours(): Signature {
        val json = JSONObject()
            .put("kind", PatternKind.TIME_OF_DAY_CLUSTER.serial)
            .put("bucket", "goblin")
            .toString()
        return Signature(PatternKind.TIME_OF_DAY_CLUSTER, json, sha256(json), null)
    }

    fun forCommitment(topicOrPerson: String): Signature {
        val canonical = TagNormalize.kebab(topicOrPerson)
        val json = JSONObject()
            .put("kind", PatternKind.COMMITMENT_RECURRENCE.serial)
            .put("topic_or_person", canonical)
            .toString()
        return Signature(PatternKind.COMMITMENT_RECURRENCE, json, sha256(json), null)
    }

    fun forVocabToken(token: String): Signature {
        val canonical = token.lowercase(Locale.ROOT)
        val json = JSONObject()
            .put("kind", PatternKind.VOCAB_FREQUENCY.serial)
            .put("token", canonical)
            .toString()
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
