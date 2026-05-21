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
 * Token sources: tags, energy descriptor, entry text. All paths apply [MIN_VOCAB_LENGTH] so
 * short filler ("the", "and") and short energy descriptors can't form low-signal patterns.
 * Everything is stemmed + alias-folded via [VOCAB_ROOT_ALIASES].
 */
internal object VocabTokens {

    fun forEntry(entry: EntryEntity): Set<String> {
        val fromTags = entry.tags.asSequence()
            .map { it.name.toVocabToken() }
            .filter { it.length >= MIN_VOCAB_LENGTH }
        val fromEnergy = entry.energyDescriptor
            ?.split(WORD_SPLIT)
            ?.asSequence()
            ?.map { it.toVocabToken() }
            ?.filter { it.length >= MIN_VOCAB_LENGTH }
            ?: emptySequence()
        val fromText = entry.entryText
            .lowercase(Locale.ROOT)
            .split(WORD_SPLIT)
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= MIN_VOCAB_LENGTH }
            .map { it.toVocabToken() }
        return (fromTags + fromEnergy + fromText).toSet()
    }

    fun String.toVocabToken(): String {
        val token = TokenStemmer.stem(this.lowercase(Locale.ROOT).trim())
        return VOCAB_ROOT_ALIASES[token] ?: token
    }

    const val MIN_VOCAB_LENGTH = 4
    val WORD_SPLIT: Regex = Regex("[^a-z0-9]+")

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
