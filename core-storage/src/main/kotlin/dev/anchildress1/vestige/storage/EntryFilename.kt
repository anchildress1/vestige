package dev.anchildress1.vestige.storage

import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Markdown filename generator for `:core-storage`. Format per architecture-brief.md
 * §"Markdown Entry Shape":
 *
 *   {filesDir}/entries/{ISO8601-utc-second}--{slug}.md
 *
 * Filenames are stable for the life of an entry. Re-eval re-writes the *contents* of an
 * existing file via atomic temp-rename; it does not move the file.
 */
internal object EntryFilename {

    /** Maximum slug length per architecture-brief.md §"Filename". */
    const val SLUG_MAX_CHARS = 32

    /** Slug derives from the first 5–6 content words after stop-word strip. */
    private const val SLUG_MAX_WORDS = 6

    private const val SLUG_FALLBACK = "entry"

    /**
     * Common English stop words. Stripped before slug generation so the kebab carries the
     * salient nouns/verbs from the entry text — `the-launch-doc-stared-flattened` beats
     * `i-was-just-the-launch-doc`.
     */
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

    /**
     * Build the filename portion (no directory) for the given timestamp + entry text.
     * Caller resolves collisions via [resolveUnique].
     */
    fun buildFilename(timestampEpochMs: Long, entryText: String): String {
        val iso = formatIsoSecondUtc(timestampEpochMs)
        val slug = generateSlug(entryText)
        return "$iso--$slug.md"
    }

    /**
     * Generate a kebab-case slug ≤ [SLUG_MAX_CHARS] from the first content words of
     * [entryText]. Stop words are stripped, non-`[a-z0-9-]` characters are dropped, and an
     * empty result falls back to `entry`.
     */
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

    /**
     * Resolve a non-colliding filename by appending `-2`, `-3`, … to the slug portion.
     * Returns the input unchanged when no file exists at `dir/baseName`.
     */
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
