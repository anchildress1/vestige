package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.Lens
import dev.anchildress1.vestige.model.LensExtraction

/**
 * Parses a lens call's flat `key: value` line response into a [LensExtraction] (format:
 * `resources/lenses/output-schema.txt`). Gemma botches nested JSON far more often than simple
 * lines — free-text inside a JSON string is where it loops and drops braces — so the lens contract
 * is line-based: one field per line, value runs to the newline. Unknown lines and surrounding prose
 * are ignored; returns `null` only when nothing usable is present.
 *
 * Values map to the same shapes convergence expects: `tags` -> List<String>, `stated_commitment`
 * -> {text, topic_or_person}, skeptical `flag:` lines -> "kind:snippet:note" strings.
 * `tags`/`template_label`/`vocabulary` are trimmed + lowercased; literal nullish junk is dropped.
 * Only the Skeptical lens keeps flags.
 */
internal object LensResponseParser {

    /** Literal tokens models emit for an absent value — folded back to null / dropped. */
    private val NULLISH_WORDS: Set<String> = setOf("null", "none", "n/a", "nil")

    fun parse(lens: Lens, raw: String): LensExtraction? {
        if (raw.isBlank()) return null
        val lines = raw.lineSequence().mapNotNull(::splitLine).toList()
        val flags = if (lens == Lens.SKEPTICAL) {
            lines.filter { it.first == "flag" }.mapNotNull { encodeFlagLine(it.second) }
        } else {
            emptyList()
        }
        val fields = buildFields(lines.associate { it.first to it.second })
        return if (fields.values.all { it == null } && flags.isEmpty()) {
            null
        } else {
            LensExtraction(lens = lens, fields = fields, flags = flags)
        }
    }

    /**
     * `key: value` -> (normalized key, trimmed value); null for lines without a leading key. The key
     * is lowercased and `-`→`_` so the model's inconsistent `template-label` / `recurrence-kind`
     * spellings still bind to the schema keys instead of being silently dropped. Hyphens in the
     * value (kebab-case tags) are untouched.
     */
    private fun splitLine(line: String): Pair<String, String>? {
        val separator = line.indexOf(':')
        if (separator <= 0) return null
        val key = line.substring(0, separator).trim().lowercase().replace('-', '_')
        return key to line.substring(separator + 1).trim()
    }

    /** Last value wins per scalar key; maps to the field shapes convergence expects. */
    private fun buildFields(byKey: Map<String, String>): Map<String, Any?> {
        val commitment = byKey["commitment"]?.ifNotNullish()?.let { text ->
            mapOf("text" to text, "topic_or_person" to byKey["commitment_topic"]?.ifNotNullish())
        }
        return mapOf(
            // A `tags:` line that holds only blanks/nullish tokens is no usable signal — fold the
            // empty result back to null so an otherwise-empty parse returns null and the lens retries.
            "tags" to byKey["tags"]?.split(',')?.mapNotNull(::normalizeToken)?.takeIf { it.isNotEmpty() },
            "template_label" to byKey["template_label"]?.let(::normalizeWord),
            "vocabulary" to byKey["vocabulary"]?.let(::normalizeWord),
            // recurrence_link is no longer model-emitted — the app sets it deterministically from the
            // matched candidate pattern when the model confirms recurrence_kind. See ADR-002.
            "recurrence_kind" to byKey["recurrence_kind"]?.ifNotNullish(),
            "stated_commitment" to commitment,
        )
    }

    private fun normalizeToken(token: String): String? =
        token.trim().lowercase().takeIf { it.isNotEmpty() && it !in NULLISH_WORDS }

    private fun normalizeWord(value: String): String? =
        value.trim().lowercase().takeIf { it.isNotEmpty() && it !in NULLISH_WORDS }

    // `kind | snippet | note` — kind drives the convergence conflict binding; snippet/note are
    // evidence. Collapsed to the stable "kind:snippet:note" string the resolver already consumes.
    private fun encodeFlagLine(value: String): String? {
        val parts = value.split('|').map { it.trim() }
        val kind = parts.getOrNull(0).orEmpty()
        val snippet = parts.getOrNull(1).orEmpty()
        val note = parts.getOrNull(2).orEmpty()
        return "$kind:$snippet:$note".takeIf { kind.isNotEmpty() || snippet.isNotEmpty() || note.isNotEmpty() }
    }

    private fun String.ifNotNullish(): String? = takeIf { it.isNotEmpty() && lowercase() !in NULLISH_WORDS }
}
