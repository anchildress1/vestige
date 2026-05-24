package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.DetectedPattern
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.TemplateLabel
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import io.objectbox.query.QueryBuilder
import org.json.JSONObject
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Deterministic counting pass per ADR-003 §"Detection algorithm". Runs over a 90-day window
 * (30-day for the goblin-hours bucket). No model calls — the orchestrator generates titles and
 * callout text once a new pattern is upserted into [PatternStore].
 *
 * Tags are normalized (lowercase) at compare time. Vocab-drift detection is embedding-cluster
 * based (`EmbeddingClustering` over each entry's vector), keyed on the cluster's dominant
 * model-emitted `vocabularyWord` — not a token-frequency count.
 */
class PatternDetector(
    private val boxStore: BoxStore,
    private val clock: Clock = Clock.systemUTC(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {

    fun detect(): List<DetectedPattern> {
        val nowMs = clock.millis()
        // Indexed query — `EntryEntity.extractionStatus` is `@Index`-annotated, so this is an
        // O(log n) index seek rather than the prior O(total entries) JVM filter.
        val entries: List<EntryEntity> = boxStore.boxFor<EntryEntity>().query()
            .equal(
                EntryEntity_.extractionStatus,
                ExtractionStatus.COMPLETED.name,
                QueryBuilder.StringOrder.CASE_SENSITIVE,
            )
            .build()
            .use { it.find() }
        // Reject future-dated rows. A clock-skewed or manually-edited future timestamp would
        // otherwise satisfy `nowMs - timestamp <= window` (the delta is negative) and count
        // toward thresholds until wall clock catches up.
        val withinWindow = entries.filter { entry ->
            (nowMs - entry.timestampEpochMs) in 0..WINDOW_90D_MS
        }
        val withinGoblinWindow = entries.filter { entry ->
            (nowMs - entry.timestampEpochMs) in 0..WINDOW_30D_MS
        }

        return buildList {
            addAll(detectTemplateRecurrence(withinWindow))
            addAll(detectTagPair(withinWindow))
            detectGoblinHours(withinGoblinWindow)?.let { add(it) }
            addAll(detectCommitments(withinWindow))
            addAll(detectVocab(withinWindow))
            addAll(
                TemporalRelativePatternDetector(zoneId).detect(withinWindow).map {
                    detected(it.signature, it.supporting)
                },
            )
        }
    }

    // AUDIT is the labeler's fallback bucket (everything that didn't match an archetype),
    // not a pattern shape. Detection grouping by AUDIT would surface a meaningless cluster
    // of "everything that didn't fit elsewhere." See `docs/backlog.md` §`labeler-prompt-tightening`.
    private fun detectTemplateRecurrence(entries: List<EntryEntity>): List<DetectedPattern> = entries
        .filter { it.templateLabel != null }
        .filterNot { it.templateLabel == TemplateLabel.AUDIT }
        .groupBy { it.templateLabel!! }
        .filterValues { it.size >= SUPPORTING_THRESHOLD }
        .map { (label, supporting) ->
            val sig = PatternSignature.forTemplateRecurrence(label.serial)
            detected(sig, supporting)
        }

    private fun detectTagPair(entries: List<EntryEntity>): List<DetectedPattern> = entries
        .filter { it.templateLabel != null }
        .filterNot { it.templateLabel == TemplateLabel.AUDIT }
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
        // Convert to immutable view after the build phase — the supporting lists are only read
        // downstream, so narrow the type for callers and satisfy `kotlin:S6524`.
        return byTopic
            .mapValues { it.value.toList() }
            .filter { it.value.size >= SUPPORTING_THRESHOLD }
            .map { (topic, supporting) ->
                val sig = PatternSignature.forCommitment(topic)
                detected(sig, supporting)
            }
    }

    // Vocab-drift detection is one embedding cluster per pattern, not a token count. Candidates
    // carry a usable vector AND a model-emitted tone word; `EmbeddingClustering` groups them by
    // semantic proximity (same feeling) and each cluster's distinct `vocabularyWord`s are the
    // drift (different words). Identity stays token-keyed on the cluster's dominant word so the
    // pattern's lifecycle (skip / drop / cooldown) survives across re-detection runs.
    private fun detectVocab(entries: List<EntryEntity>): List<DetectedPattern> {
        val candidates = entries.filter { !it.vocabularyWord.isNullOrBlank() }
        val clusters = EmbeddingClustering.cluster(candidates)
        // Calibration diagnostics: counts + distances only, never entry text. Gated behind DEBUG —
        // nearestNeighborDistances is O(n²), so don't pay it on every detection pass in normal runs.
        // Enable for an on-device calibration read: `adb shell setprop log.tag.VestigePatternDetector DEBUG`.
        if (android.util.Log.isLoggable("VestigePatternDetector", android.util.Log.DEBUG)) {
            android.util.Log.d(
                "VestigePatternDetector",
                "vocab cluster: candidates=${candidates.size} " +
                    "clusterSizes=${clusters.map { it.members.size }} " +
                    "threshold=$VOCAB_THRESHOLD " +
                    "maxCosine=${EmbeddingClustering.DEFAULT_MAX_COSINE_DISTANCE} " +
                    "nnDistances=${EmbeddingClustering.nearestNeighborDistances(candidates)}",
            )
        }
        return clusters
            .filter { it.members.size >= VOCAB_THRESHOLD }
            .map { cluster ->
                val sig = PatternSignature.forVocabToken(dominantVocabularyWord(cluster.members))
                detected(sig, cluster.members)
            }
    }

    // Most frequent canonical tone word across the cluster; alphabetical tie-break for
    // determinism. Grouping on the canonical form (not the raw word) so `Tired` / `tireds` /
    // `tired` collapse into one count and the identity matches what the matcher compares.
    // Members are pre-filtered to non-blank tone words, so the comparator always has input.
    private fun dominantVocabularyWord(members: List<EntryEntity>): String = members
        .groupingBy { PatternSignature.canonicalVocabToken(it.vocabularyWord!!) }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .first()
        .key

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
        const val WINDOW_90D_MS: Long = 90L * 24 * 60 * 60 * 1000
        const val WINDOW_30D_MS: Long = 30L * 24 * 60 * 60 * 1000
    }
}

// Top-level helper — kept off the class to satisfy the function-count budget.
private fun parseCommitmentTopic(json: String?): String? {
    val text = json?.takeIf { it.isNotBlank() } ?: return null
    // Upstream wrote the commitment JSON; a parse failure means the contract broke. Don't
    // crash detection over it, but don't hide it either — Phase 4 re-eval will see the same
    // corruption.
    val obj = runCatching { JSONObject(text) }.getOrElse { e ->
        android.util.Log.w(
            "VestigePatternDetector",
            "malformed statedCommitmentJson (len=${text.length}): ${e.javaClass.simpleName}",
        )
        null
    }
    return obj?.optString("topic_or_person")?.trim()?.takeIf { it.isNotEmpty() }
}
