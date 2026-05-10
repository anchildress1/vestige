package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.Lens
import dev.anchildress1.vestige.model.LensExtraction
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Parses a lens call's raw response into a [LensExtraction] (schema:
 * `resources/lenses/output-schema.txt`). Tolerant of surrounding prose / markdown fences — finds
 * the first balanced `{...}` that parses as a JSON object, skipping earlier blocks that don't
 * (e.g. schema commentary like `{kind, snippet, note}`). Rejects array-wrapped payloads
 * (`[{...}]`) so a malformed top-level shape can't masquerade as valid extraction data. Returns
 * `null` on any parse failure — the worker treats that as "no opinion."
 *
 * `tags` are normalized at parse time (trim + lowercase, empty strings dropped) so a "Standup"
 * from one lens equals a "standup" from another at convergence-time string comparison.
 *
 * Schema-shaped flag objects (`{kind, snippet, note}`) collapse to a stable
 * `"$kind:$snippet:$note"` string. Only Skeptical lens output keeps its flags; Literal /
 * Inferential `flags` are dropped — the schema makes this single-lens contract explicit and
 * propagating drift would corrupt convergence.
 */
internal object LensResponseParser {

    private val SCHEMA_KEYS: Set<String> = setOf(
        "tags",
        "energy_descriptor",
        "state_shift",
        "vocabulary_contradictions",
        "stated_commitment",
        "recurrence_link",
        "recurrence_kind",
    )

    fun parse(lens: Lens, raw: String): LensExtraction? {
        val root = findFirstParseableObject(raw) ?: return null
        val fields = SCHEMA_KEYS.associateWith { key -> normalizeField(key, root.opt(key)) }
        val flags = if (lens == Lens.SKEPTICAL) {
            (normalize(root.opt("flags")) as? List<*>)?.mapNotNull(::encodeFlag) ?: emptyList()
        } else {
            emptyList()
        }
        return LensExtraction(lens = lens, fields = fields, flags = flags)
    }

    /** Per-field normalization. Tags get trimmed + lowercased; everything else passes through. */
    private fun normalizeField(key: String, value: Any?): Any? {
        val normalized = normalize(value)
        return if (key == "tags") (normalized as? List<*>)?.mapNotNull(::normalizeTag) else normalized
    }

    private fun normalizeTag(entry: Any?): String? = (entry as? String)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    private fun encodeFlag(entry: Any?): String? = when (entry) {
        is String -> entry.takeIf { it.isNotBlank() }

        is Map<*, *> -> {
            val kind = entry["kind"]?.toString().orEmpty()
            val snippet = entry["snippet"]?.toString().orEmpty()
            val note = entry["note"]?.toString().orEmpty()
            "$kind:$snippet:$note".takeIf { kind.isNotEmpty() || snippet.isNotEmpty() || note.isNotEmpty() }
        }

        else -> null
    }

    /**
     * Walk forward, find every balanced `{...}` block, return the first that parses as a
     * JSONObject. Rejects array-wrapped payloads (`[{...}]`) at each candidate brace block:
     * a `[` whose only-whitespace separation from that `{` indicates the bracket opens the
     * payload itself, not prose like `[note] {...}`.
     */
    private fun findFirstParseableObject(raw: String): JSONObject? {
        if (raw.isBlank()) return null
        var cursor = 0
        var found: JSONObject? = null
        var keepScanning = true
        while (keepScanning && found == null) {
            val open = raw.indexOf('{', cursor).takeIf { it >= 0 }
            val close = open?.let { scanBalancedClose(raw, it) }
            if (open == null || close == null) {
                keepScanning = false
            } else {
                if (!isArrayWrapped(raw, open)) {
                    val candidate = raw.substring(open, close + 1)
                    found = runCatching { JSONTokener(candidate).nextValue() as? JSONObject }.getOrNull()
                }
                cursor = open + 1
            }
        }
        return found
    }

    /**
     * The payload is array-wrapped when the closest non-whitespace character preceding the first
     * `{` is `[`. Looking at *only* the immediate predecessor (rather than the first `[` in the
     * whole response) keeps prose like `[note] [{...}]` honest — the inner array still wraps the
     * brace, but `[note] {...}` does not.
     */
    private fun isArrayWrapped(raw: String, firstBrace: Int): Boolean {
        var i = firstBrace - 1
        while (i >= 0 && raw[i].isWhitespace()) i -= 1
        return i >= 0 && raw[i] == '['
    }

    private fun scanBalancedClose(raw: String, openIdx: Int): Int? {
        val state = ScanState()
        var closeIdx = -1
        for (i in openIdx until raw.length) {
            advance(state, raw[i])
            if (state.closed) {
                closeIdx = i
                break
            }
        }
        return closeIdx.takeIf { it >= 0 }
    }

    private fun advance(state: ScanState, c: Char) {
        if (state.escape) {
            state.escape = false
            return
        }
        if (state.inString) {
            advanceInsideString(state, c)
            return
        }
        when (c) {
            '"' -> state.inString = true

            '{' -> state.depth += 1

            '}' -> {
                state.depth -= 1
                if (state.depth == 0) state.closed = true
            }
        }
    }

    private fun advanceInsideString(state: ScanState, c: Char) {
        when (c) {
            '\\' -> state.escape = true
            '"' -> state.inString = false
        }
    }

    private class ScanState(
        var depth: Int = 0,
        var inString: Boolean = false,
        var escape: Boolean = false,
        var closed: Boolean = false,
    )

    private fun normalize(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { key -> normalize(value.opt(key)) }
        is JSONArray -> List(value.length()) { idx -> normalize(value.opt(idx)) }
        else -> value
    }
}
