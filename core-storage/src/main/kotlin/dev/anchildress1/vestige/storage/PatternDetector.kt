package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.DetectedPattern
import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.TemplateLabel
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import org.json.JSONObject
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Deterministic counting pass per ADR-003 §"Detection algorithm". Runs over a 90-day window
 * (30-day for the goblin-hours bucket). No model calls — the orchestrator generates titles and
 * callout text once a new pattern is upserted into [PatternStore].
 *
 * Tags are normalized (lowercase) at compare time; vocabulary tokens are folded through the
 * shared stemmer rules so `tired` / `Tired` / `tireds` collapse into one signature.
 */
class PatternDetector(
    private val boxStore: BoxStore,
    private val clock: Clock = Clock.systemUTC(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {

    fun detect(): List<DetectedPattern> {
        val nowMs = clock.millis()
        val entries = boxStore.boxFor<EntryEntity>().all
        val withinWindow = entries.filter { nowMs - it.timestampEpochMs <= WINDOW_90D_MS }
        val withinGoblinWindow = entries.filter { nowMs - it.timestampEpochMs <= WINDOW_30D_MS }

        return buildList {
            addAll(detectTemplateRecurrence(withinWindow))
            addAll(detectTagPair(withinWindow))
            detectGoblinHours(withinGoblinWindow)?.let { add(it) }
            addAll(detectCommitments(withinWindow))
            addAll(detectVocab(withinWindow))
        }
    }

    private fun detectTemplateRecurrence(entries: List<EntryEntity>): List<DetectedPattern> = entries
        .filter { it.templateLabel != null }
        .groupBy { it.templateLabel!! }
        .filterValues { it.size >= SUPPORTING_THRESHOLD }
        .map { (label, supporting) ->
            val sig = PatternSignature.forTemplateRecurrence(label.serial)
            detected(sig, supporting)
        }

    private fun detectTagPair(entries: List<EntryEntity>): List<DetectedPattern> = entries
        .filter { it.templateLabel != null }
        .groupBy { it.templateLabel!! }
        .flatMap { (label, group) ->
            pairsWithinGroup(group)
                .filterValues { it.size >= SUPPORTING_THRESHOLD }
                .map { (pair, supporting) ->
                    val sig = PatternSignature.forTagPair(label.serial, setOf(pair.first, pair.second))
                    detected(sig, supporting)
                }
        }

    private fun pairsWithinGroup(group: List<EntryEntity>): Map<Pair<String, String>, List<EntryEntity>> {
        val pairs = linkedMapOf<Pair<String, String>, MutableList<EntryEntity>>()
        for (entry in group) {
            val tags = entry.tags
                .map { TagNormalize.kebab(it.name) }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
            if (tags.size < TAG_PAIR_SIZE) continue
            for (i in tags.indices) {
                for (j in (i + 1) until tags.size) {
                    pairs.getOrPut(tags[i] to tags[j]) { mutableListOf() }.add(entry)
                }
            }
        }
        return pairs
    }

    private fun detectGoblinHours(entries: List<EntryEntity>): DetectedPattern? {
        val supporting = entries.filter {
            Instant.ofEpochMilli(it.timestampEpochMs).atZone(zoneId).hour in
                TemplateLabel.GOBLIN_HOURS_LOCAL_HOUR_RANGE
        }
        if (supporting.size < SUPPORTING_THRESHOLD) return null
        val sig = PatternSignature.forGoblinHours()
        return detected(sig, supporting)
    }

    private fun detectCommitments(entries: List<EntryEntity>): List<DetectedPattern> {
        val byTopic = linkedMapOf<String, MutableList<EntryEntity>>()
        for (entry in entries) {
            val topic = parseCommitmentTopic(entry.statedCommitmentJson)
                ?.let(TagNormalize::kebab)
                ?.takeIf { it.isNotEmpty() }
                ?: continue
            byTopic.getOrPut(topic) { mutableListOf() }.add(entry)
        }
        return byTopic
            .filter { it.value.size >= SUPPORTING_THRESHOLD }
            .map { (topic, supporting) ->
                val sig = PatternSignature.forCommitment(topic)
                detected(sig, supporting)
            }
    }

    private fun detectVocab(entries: List<EntryEntity>): List<DetectedPattern> {
        val tokenToEntries = linkedMapOf<String, MutableList<EntryEntity>>()
        for (entry in entries) {
            val tokens = vocabTokensFor(entry)
            for (token in tokens) {
                tokenToEntries.getOrPut(token) { mutableListOf() }.add(entry)
            }
        }
        // ADR-003 §"Vocabulary-frequency context check" guards against "a single long sentence
        // using 'tired' four times." `vocabTokensFor` returns a Set, so each entry contributes
        // at most once per token — the ≥4 distinct-entry threshold already enforces the
        // diversity requirement. Templated-context narrowing rejected legitimate cases (e.g.
        // four entries with `templateLabel = null`, which is the predominant shape early in
        // the corpus); using entry distinctness instead matches the ADR's stated intent.
        return tokenToEntries
            .filter { (_, supporting) -> supporting.distinctBy { it.id }.size >= VOCAB_THRESHOLD }
            .map { (token, supporting) ->
                val sig = PatternSignature.forVocabToken(token)
                detected(sig, supporting)
            }
    }

    private fun vocabTokensFor(entry: EntryEntity): Set<String> {
        val fromTags = entry.tags.map { TokenStemmer.stem(it.name) }
        val fromText = entry.entryText
            .lowercase(Locale.ROOT)
            .split(WORD_SPLIT)
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= MIN_VOCAB_LENGTH }
            .map { TokenStemmer.stem(it) }
        return (fromTags.asSequence() + fromText).toSet()
    }

    private fun detected(signature: Signature, supporting: List<EntryEntity>): DetectedPattern {
        val ids = supporting.map { it.id }.sorted()
        return DetectedPattern(
            patternId = signature.patternId,
            kind = signature.kind,
            signatureJson = signature.json,
            templateLabel = signature.templateLabel,
            supportingEntryIds = ids,
            firstSeenTimestamp = supporting.minOf { it.timestampEpochMs },
            lastSeenTimestamp = supporting.maxOf { it.timestampEpochMs },
        )
    }

    companion object {
        const val SUPPORTING_THRESHOLD = 3
        const val VOCAB_THRESHOLD = 4
        const val TAG_PAIR_SIZE = 2
        const val DETECTION_INTERVAL = 10
        const val WINDOW_90D_MS: Long = 90L * 24 * 60 * 60 * 1000
        const val WINDOW_30D_MS: Long = 30L * 24 * 60 * 60 * 1000

        internal const val MIN_VOCAB_LENGTH = 4
        internal val WORD_SPLIT: Regex = Regex("[^a-z0-9]+")
    }
}

// Top-level helper — kept off the class to satisfy the function-count budget.
private const val LOG_PREVIEW_CHARS = 80

private fun parseCommitmentTopic(json: String?): String? {
    val text = json?.takeIf { it.isNotBlank() } ?: return null
    val obj = runCatching { JSONObject(text) }.getOrNull()
    // Upstream wrote the commitment JSON; a parse failure means the contract broke. Don't
    // crash detection over it, but don't hide it either — Phase 4 re-eval will see the same
    // corruption.
    return when (obj) {
        null -> {
            android.util.Log.w(
                "VestigePatternDetector",
                "malformed statedCommitmentJson: ${text.take(LOG_PREVIEW_CHARS)}",
            )
            null
        }
        else -> obj.optString("topic_or_person").trim().takeIf { it.isNotEmpty() }
    }
}
