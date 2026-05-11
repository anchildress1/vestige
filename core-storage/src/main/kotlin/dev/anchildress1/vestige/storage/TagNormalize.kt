package dev.anchildress1.vestige.storage

import java.util.Locale

/**
 * Canonical tag / label normalization. ADR-003 §"`pattern_id` generation" requires tags to be
 * lowercased, kebab-case, and sorted **before** hashing — so `tuesday meeting` and
 * `tuesday-meeting` collapse to the same signature. Detector, matcher, and signature share this
 * one function so the hash inputs never drift.
 */
internal object TagNormalize {

    /** Lowercase + replace runs of whitespace / underscores with a single hyphen + collapse repeats. */
    fun kebab(raw: String): String {
        val lower = raw.lowercase(Locale.ROOT).trim()
        if (lower.isEmpty()) return ""
        return lower
            .replace(WHITESPACE_OR_UNDERSCORE, "-")
            .replace(REPEATED_HYPHEN, "-")
            .trim('-')
    }

    private val WHITESPACE_OR_UNDERSCORE: Regex = Regex("[\\s_]+")
    private val REPEATED_HYPHEN: Regex = Regex("-{2,}")
}
