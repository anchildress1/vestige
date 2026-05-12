package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.TemplateLabel
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * ADR-003 §"Matching predicate". One pure function per [PatternKind] — operates on the entity
 * row + the persisted [PatternEntity.signatureJson] so callers do not need to recompute the
 * signature. Used by the per-entry callout selector in [PatternDetectionOrchestrator].
 *
 * Tag matching uses subset semantics (an entry with `{a, b, c}` still supports a pattern whose
 * signature pair is `{a, b}` per ADR-003 §"Subset semantics for tag pairs").
 */
object PatternMatcher {

    fun matches(entry: EntryEntity, pattern: PatternEntity, zoneId: ZoneId): Boolean {
        val signature = runCatching { JSONObject(pattern.signatureJson) }.getOrNull()
        if (signature == null) {
            android.util.Log.w(
                "VestigePatternMatcher",
                "malformed signatureJson for patternId=${pattern.patternId}: " +
                    pattern.signatureJson.take(LOG_PREVIEW_CHARS),
            )
            return false
        }
        return when (pattern.kind) {
            PatternKind.TEMPLATE_RECURRENCE -> matchesTemplate(entry, signature)
            PatternKind.TAG_PAIR_CO_OCCURRENCE -> matchesTagPair(entry, signature)
            PatternKind.TIME_OF_DAY_CLUSTER -> matchesGoblin(entry, zoneId)
            PatternKind.COMMITMENT_RECURRENCE -> matchesCommitment(entry, signature)
            PatternKind.VOCAB_FREQUENCY -> matchesVocab(entry, signature)
        }
    }

    private fun matchesTemplate(entry: EntryEntity, signature: JSONObject): Boolean {
        val target = TagNormalize.kebab(signature.optString("label"))
        return entry.templateLabel?.serial == target
    }

    private fun matchesTagPair(entry: EntryEntity, signature: JSONObject): Boolean {
        val label = TagNormalize.kebab(signature.optString("label"))
        val tagsArray = signature.optJSONArray("tags")
        val labelMatches = entry.templateLabel?.serial == label
        // `tag_pair_co_occurrence` is, by definition, exactly two tags. A 1-tag signature would
        // match any entry containing that tag (collapsing to a template_recurrence-ish match);
        // a 3+ tag signature would over-constrain. Either shape indicates upstream corruption
        // and should not silently match.
        if (!labelMatches || tagsArray == null || tagsArray.length() != TAG_PAIR_SIZE) {
            if (labelMatches && tagsArray != null && tagsArray.length() != TAG_PAIR_SIZE) {
                android.util.Log.w(
                    "VestigePatternMatcher",
                    "tag_pair signature has ${tagsArray.length()} tags (expected $TAG_PAIR_SIZE) — rejecting match",
                )
            }
            return false
        }
        val pair = (0 until tagsArray.length()).mapTo(linkedSetOf()) {
            TagNormalize.kebab(tagsArray.optString(it))
        }
        val entryTags = entry.tags.map { TagNormalize.kebab(it.name) }.toSet()
        return pair.size == TAG_PAIR_SIZE && entryTags.containsAll(pair)
    }

    private fun matchesGoblin(entry: EntryEntity, zoneId: ZoneId): Boolean {
        val hour = Instant.ofEpochMilli(entry.timestampEpochMs).atZone(zoneId).hour
        return hour in TemplateLabel.GOBLIN_HOURS_LOCAL_HOUR_RANGE
    }

    private fun matchesCommitment(entry: EntryEntity, signature: JSONObject): Boolean {
        val target = TagNormalize.kebab(signature.optString("topic_or_person"))
        val raw = entry.statedCommitmentJson?.takeIf { it.isNotBlank() }
        val commitment = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (raw != null && commitment == null) {
            android.util.Log.w(
                "VestigePatternMatcher",
                "malformed statedCommitmentJson on entry id=${entry.id}: ${raw.take(LOG_PREVIEW_CHARS)}",
            )
        }
        val topic = commitment?.optString("topic_or_person")?.trim()?.let(TagNormalize::kebab)
        return target.isNotEmpty() && topic == target
    }

    private fun matchesVocab(entry: EntryEntity, signature: JSONObject): Boolean {
        val token = signature.optString("token").lowercase(Locale.ROOT)
        if (token.isEmpty()) return false
        // Apply the same stemmer the detector used to mint the signature so a `tireds` /
        // `Tired` entry text matches a `tired` signature without an extra prefix-startsWith
        // heuristic the detector would not have produced.
        val tagHit = entry.tags.any { TokenStemmer.stem(it.name) == token }
        val textHit = entry.entryText.lowercase(Locale.ROOT)
            .split(VOCAB_SPLIT)
            .any { TokenStemmer.stem(it.trim()) == token }
        return tagHit || textHit
    }

    private val VOCAB_SPLIT: Regex = Regex("[^a-z0-9]+")
    private const val LOG_PREVIEW_CHARS = 80
    private const val TAG_PAIR_SIZE = 2
}
