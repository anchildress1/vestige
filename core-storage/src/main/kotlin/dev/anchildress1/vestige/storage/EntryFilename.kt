package dev.anchildress1.vestige.storage

import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Generates `{ISO8601-utc-second}--{slug}.md` filenames. Filenames are stable for the life of
 * an entry; re-eval rewrites contents in place.
 */
internal object EntryFilename {

    const val SLUG_MAX_CHARS = 32
    private const val SLUG_MAX_WORDS = 6
    private const val SLUG_FALLBACK = "entry"

    // Stripped so the slug carries salient nouns/verbs, not filler.
    private val STOP_WORDS = setOf(
        "a", "about", "after", "an", "and", "are", "as", "at",
        "be", "been", "before", "being", "but", "by",
        "can", "could",
        "did", "do", "does", "during",
        "for", "from",
        "had", "has", "have", "he", "her", "him", "his",
        "i", "in", "into", "is", "it", "its",
        "just",
        "may", "me", "might", "must", "my",
        "of", "on", "or", "our",
        "shall", "she", "should", "so",
        "than", "that", "the", "their", "them", "these", "they", "this", "those", "through", "to",
        "until", "up", "us",
        "was", "we", "were", "what", "which", "while", "who", "will", "with", "would",
        "you", "your",
    )

    fun buildFilename(timestampEpochMs: Long, entryText: String): String {
        val iso = formatIsoSecondUtc(timestampEpochMs)
        val slug = generateSlug(entryText)
        return "$iso--$slug.md"
    }

    fun generateSlug(entryText: String): String {
        val words = entryText
            .lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() && it !in STOP_WORDS && it != "-" }
        val joined = words
            .take(SLUG_MAX_WORDS)
            .joinToString("-")
            .take(SLUG_MAX_CHARS)
            .trim('-')
        return joined.ifEmpty { SLUG_FALLBACK }
    }

    /** Returns [baseName] unchanged on no collision, otherwise appends `-2`, `-3`, … */
    fun resolveUnique(dir: File, baseName: String): String {
        if (!File(dir, baseName).exists()) return baseName
        val (stem, dotMd) = baseName.split(".md").let { it[0] to ".md" }
        var suffix = 2
        while (true) {
            val candidate = "$stem-$suffix$dotMd"
            if (!File(dir, candidate).exists()) return candidate
            suffix++
        }
    }

    private fun formatIsoSecondUtc(timestampEpochMs: Long): String {
        val instant = Instant.ofEpochMilli(timestampEpochMs).truncatedTo(ChronoUnit.SECONDS)
        return DateTimeFormatter.ISO_INSTANT.format(instant).replace(':', '-')
    }
}
