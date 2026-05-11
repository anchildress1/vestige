package dev.anchildress1.vestige.storage

import java.util.Locale

/**
 * Plural folding for vocabulary matching. Detector and matcher share this so the predicate is
 * one function. The `news` / `series` / `species` carve-outs preserve nouns that naive
 * singularization corrupts (per `ADR-002` §"Plural folding addendum"). The short-token guard
 * (≤3 chars) keeps `bus` / `gas` from collapsing to a one-letter stem.
 */
internal object TokenStemmer {

    fun stem(token: String): String {
        val lower = token.lowercase(Locale.ROOT)
        return when {
            lower in PRESERVED_SURFACES -> lower
            lower.length <= MIN_STEM_LENGTH -> lower
            lower.endsWith("ss") || lower.endsWith("us") || lower.endsWith("is") -> lower
            lower.endsWith('s') -> lower.dropLast(1)
            else -> lower
        }
    }

    private const val MIN_STEM_LENGTH = 3
    private val PRESERVED_SURFACES: Set<String> = setOf("news", "series", "species")
}
