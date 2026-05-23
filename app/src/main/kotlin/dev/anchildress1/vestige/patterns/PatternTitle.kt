package dev.anchildress1.vestige.patterns

/**
 * Strips the category nouns the title model tacks on ("Tuesday Morning Pattern",
 * "Standup Recurrence") so a pattern card never labels itself with its own category. Falls back to
 * the raw title when stripping would leave it blank.
 */
internal object PatternTitle {

    fun sanitize(raw: String): String {
        val stripped = CATEGORY_WORDS.replace(raw, " ")
            .replace(WHITESPACE_RUN, " ")
            .trim()
            .trim('-')
            .trim()
        return stripped.ifBlank { raw.trim() }
    }

    private val CATEGORY_WORDS =
        Regex("""(?i)\b(?:patterns?|recurrences?|activit(?:y|ies)|templates?)\b""")
    private val WHITESPACE_RUN = Regex("""\s+""")
}
