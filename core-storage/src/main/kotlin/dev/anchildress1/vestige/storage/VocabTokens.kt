package dev.anchildress1.vestige.storage

import java.util.Locale

/**
 * Single source of truth for the vocabulary token set extracted from an [EntryEntity].
 *
 * Lives outside [PatternDetector] / [PatternMatcher] so the two cannot drift: detection and
 * matching MUST extract the same tokens, otherwise a `VOCAB_FREQUENCY` pattern minted from
 * `drained` (alias-folded to `tired`) would never match subsequent `drained` entries — the
 * pattern silently stops firing for the very evidence that created it.
 *
 * Token sources: tags, entry text. All paths apply [MIN_VOCAB_LENGTH] so short filler
 * ("the", "and") can't form low-signal patterns. Everything is stemmed + alias-folded via
 * [VOCAB_ROOT_ALIASES].
 */
internal object VocabTokens {

    fun forEntry(entry: EntryEntity): Set<String> {
        val fromTags = entry.tags.asSequence()
            .map { it.name.toVocabToken() }
            .filter { it.length >= MIN_VOCAB_LENGTH }
        val fromText = entry.entryText
            .lowercase(Locale.ROOT)
            .split(WORD_SPLIT)
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= MIN_VOCAB_LENGTH }
            .map { it.toVocabToken() }
        return (fromTags + fromText).filterNot { it in STOPWORDS }.toSet()
    }

    fun String.toVocabToken(): String {
        val token = TokenStemmer.stem(this.lowercase(Locale.ROOT).trim())
        return VOCAB_ROOT_ALIASES[token] ?: token
    }

    const val MIN_VOCAB_LENGTH = 4
    val WORD_SPLIT: Regex = Regex("[^a-z0-9]+")

    // Closed-class function words + ultra-generic fillers that clear MIN_VOCAB_LENGTH but carry no
    // vocabulary signal. Without this, a VOCAB_FREQUENCY pattern mints on "just"/"that"/"time" —
    // the callout then claims the user has a "vocabulary" of filler. Compared post stem+alias, so
    // singular forms cover their plurals ("thing" ⊇ "things"). State vocabulary (tired, drained,
    // foggy, crashed, wired, …) is deliberately absent — that is the signal the pattern exists for.
    val STOPWORDS: Set<String> = setOf(
        "about", "actually", "after", "again", "also", "anymore", "anyone", "anything", "anyway",
        "are", "aren", "around", "away", "back", "because", "been", "before", "between", "both",
        "could", "couldn", "didn", "does", "doesn", "down", "each", "even", "every", "everyone",
        "everything", "from", "gonna", "gotta", "have", "haven", "here", "into", "just", "kind",
        "kinda", "like", "maybe", "more", "most", "much", "nothing", "okay", "onto", "only", "other",
        "over", "really", "same", "should", "shouldn", "some", "someone", "something", "somehow",
        "sort", "still", "stuff", "such", "sure", "than", "that", "their", "them", "then", "there",
        "these", "they", "thing", "this", "those", "through", "time", "today", "tonight", "upon",
        "very", "wanna", "wasn", "well", "were", "weren", "what", "when", "where", "which", "while",
        "with", "would", "wouldn", "your",
    )

    // True-synonym fold for "tired" — thesaurus equivalents only. Lexical near-misses
    // (different inflections, plurals) are handled by [TokenStemmer.stem] upstream.
    // Semantic rewrites that change product claims (e.g., "wired" → "tired" inverts
    // arousal direction) do NOT belong here. Pattern callouts source counts; aliasing
    // arousal-up onto arousal-down silently lies about the user's vocabulary.
    val VOCAB_ROOT_ALIASES: Map<String, String> = mapOf(
        "burnt" to "tired",
        "depleted" to "tired",
        "drained" to "tired",
        "exhausted" to "tired",
        "sluggish" to "tired",
        "wiped" to "tired",
    )
}
