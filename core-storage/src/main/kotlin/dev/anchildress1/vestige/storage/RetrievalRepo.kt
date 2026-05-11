package dev.anchildress1.vestige.storage

import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import java.time.Clock
import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic, model-free retrieval over saved entries. Ranks candidates by keyword overlap on
 * `entry_text` + tag-set Jaccard + recency boost. The vector branch lands in Story 3.4 only if
 * STT-E (Story 3.3) passes.
 *
 * Ranking is stable for identical inputs: ties on score break by entry id ascending.
 */
class RetrievalRepo(private val boxStore: BoxStore, private val clock: Clock = Clock.systemUTC()) {

    /**
     * Return the top-[topN] entries for [text]. Matches require non-zero keyword *or* tag overlap;
     * recency alone never surfaces an entry. Empty / blank queries and DBs with zero matches yield
     * an empty list (never nulls). [recencyWeight] scales the recency boost — default `0.3` per
     * ADR-002 §Q2 retrieval-budget defaults.
     */
    fun query(
        text: String,
        topN: Int = DEFAULT_TOP_N,
        recencyWeight: Float = DEFAULT_RECENCY_WEIGHT,
    ): List<EntryEntity> {
        require(topN > 0) { "RetrievalRepo.query requires topN > 0 (got $topN)" }
        require(recencyWeight in 0f..1f) { "RetrievalRepo.query requires recencyWeight in [0,1] (got $recencyWeight)" }

        val queryTerms = tokenizeToList(text)
        if (queryTerms.isEmpty()) return emptyList()
        val queryTokens = queryTerms.toSet()

        val entryBox = boxStore.boxFor<EntryEntity>()
        val tagBox = boxStore.boxFor<TagEntity>()
        val storedTagKeys = tagBox.all.mapNotNullTo(linkedSetOf()) { QueryTagMatcher.storedKey(it.name) }
        val queryTagKeys = QueryTagMatcher.queryKeysMatching(queryTerms, storedTagKeys)

        val nowMs = clock.millis()
        val scored = entryBox.all.mapNotNull { entry ->
            val entryTokens = tokenizeToList(entry.entryText).toSet()
            val keywordScore = jaccardishKeyword(queryTokens, entryTokens)
            val entryTagKeys = entry.tags.mapNotNullTo(linkedSetOf()) { QueryTagMatcher.storedKey(it.name) }
            val tagScore = jaccard(queryTagKeys, entryTagKeys)
            if (keywordScore == 0.0 && tagScore == 0.0) return@mapNotNull null
            val recency = recencyNorm(entry.timestampEpochMs, nowMs)
            val score = keywordScore + tagScore + recencyWeight * recency
            Scored(entry, score)
        }

        return scored
            .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.entry.id })
            .take(topN)
            .map { it.entry }
    }

    private fun jaccardishKeyword(queryTokens: Set<String>, entryTokens: Set<String>): Double {
        if (queryTokens.isEmpty()) return 0.0
        val intersect = queryTokens.intersect(entryTokens).size
        return intersect.toDouble() / queryTokens.size
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        val intersect = a.intersect(b).size
        val union = a.size + b.size - intersect
        return if (union == 0) 0.0 else intersect.toDouble() / union
    }

    private fun recencyNorm(timestampMs: Long, nowMs: Long): Double {
        val ageDays = (nowMs - timestampMs).toDouble() / MILLIS_PER_DAY
        return max(0.0, min(1.0, 1.0 - ageDays / RECENCY_WINDOW_DAYS))
    }

    private data class Scored(val entry: EntryEntity, val score: Double)

    private companion object {
        const val DEFAULT_TOP_N = 3
        const val DEFAULT_RECENCY_WEIGHT = 0.3f
        const val MILLIS_PER_DAY = 86_400_000.0
        const val RECENCY_WINDOW_DAYS = 90.0
    }
}

private val TOKEN_SPLIT: Regex = Regex("[^a-z0-9-]+")

private fun tokenizeToList(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    return text.lowercase()
        .split(TOKEN_SPLIT)
        .asSequence()
        .map { it.trim('-') }
        .filter { it.isNotEmpty() }
        .toList()
}

private object QueryTagMatcher {
    private const val MIN_STEM_LENGTH = 3
    private const val IES_SUFFIX = "ies"

    // ADR-002 §"Plural folding addendum" names singular tags that naive singularizers corrupt
    // (news → new, series → sery). Comparison-only: stored surface forms are never rewritten.
    private val PRESERVED_SURFACES: Set<String> = setOf("news", "series", "species")

    /** Comparison key for a stored tag name. Null when the tag normalizes to nothing. */
    fun storedKey(tagName: String): String? = comparisonKey(tagName)

    /**
     * Build the set of comparison keys derived from `queryTerms` (all 1..maxStored-word windows,
     * stemmed) that match at least one entry in `storedKeys`. Filtering against the stored
     * vocabulary keeps spurious N-gram windows (e.g., 1-grams when only multi-word tags exist)
     * out of the Jaccard union.
     */
    fun queryKeysMatching(queryTerms: List<String>, storedKeys: Set<String>): Set<String> {
        if (queryTerms.isEmpty() || storedKeys.isEmpty()) return emptySet()
        val maxTagWords = storedKeys.maxOf { it.count { ch -> ch == '-' } + 1 }
        val cappedMaxWords = min(maxTagWords, queryTerms.size)
        val keys = linkedSetOf<String>()
        for (windowSize in 1..cappedMaxWords) {
            for (start in 0..queryTerms.size - windowSize) {
                val phrase = queryTerms.subList(start, start + windowSize).joinToString("-")
                val key = comparisonKey(phrase) ?: continue
                if (key in storedKeys) keys.add(key)
            }
        }
        return keys
    }

    private fun comparisonKey(text: String): String? =
        normalizeTagPhrase(text)?.split('-')?.joinToString("-") { stemForCompare(it) }

    private fun normalizeTagPhrase(text: String): String? =
        tokenizeToList(text).takeIf { it.isNotEmpty() }?.joinToString("-")

    private fun stemForCompare(token: String): String = when {
        token in PRESERVED_SURFACES -> token
        token.length <= MIN_STEM_LENGTH -> token
        token.endsWith("ss") || token.endsWith("us") || token.endsWith("is") -> token
        token.endsWith(IES_SUFFIX) -> token.dropLast(IES_SUFFIX.length) + "y"
        token.endsWith('s') -> token.dropLast(1)
        else -> token
    }
}
