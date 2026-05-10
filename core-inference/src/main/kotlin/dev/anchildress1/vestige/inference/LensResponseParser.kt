package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.Lens
import dev.anchildress1.vestige.model.LensExtraction
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Parses one lens call's raw model response into a [LensExtraction] per the schema in
 * `core-inference/src/main/resources/lenses/output-schema.txt`. The schema asks for a single JSON
 * object containing the eight extraction keys; in practice E4B sometimes wraps the object in
 * surrounding prose ("Here's the JSON:") or markdown fences. The parser tolerates that by
 * extracting the first balanced `{...}` block and handing it to the JSON parser. Anything that
 * doesn't reduce to one parseable object is a parse failure and the caller (worker) treats the
 * lens as "no opinion" per ADR-002 §"Convergence edge cases".
 *
 * Field shape conventions in the resulting [LensExtraction.fields] map (every schema key is
 * present; missing or JSON-null values come through as `null`):
 *
 * - `tags` → `List<*>?` — passed through as the model emitted it. The schema instructs the model
 *   to use "short lowercase strings, kebab-case for multi-word tokens"; the parser does not
 *   re-normalize so convergence sees the model's emit verbatim. A missing key is `null`, not an
 *   empty list — the convergence resolver treats `null` and absence as equivalent.
 * - `energy_descriptor` → `String?`.
 * - `state_shift` → `Boolean?`.
 * - `vocabulary_contradictions` → `List<Map<String, Any?>>?`.
 * - `stated_commitment` → `Map<String, Any?>?`.
 * - `recurrence_link` → `String?`.
 * - `recurrence_kind` → `String?`.
 *
 * Skeptical-only `flags` are routed off the [LensExtraction.fields] map and onto its
 * [LensExtraction.flags] list since they don't participate in field-level convergence. The
 * schema specifies each flag as `{kind, snippet, note}`; the parser encodes that object into a
 * stable `"$kind:$snippet:$note"` string so [LensExtraction.flags] stays a `List<String>` and
 * convergence-time equality comparisons are deterministic regardless of JSON key order. Missing
 * sub-keys collapse to empty segments so the colon count stays at three; entries that resolve
 * to all-empty segments are dropped. A bare-string flag entry (the legacy convention) passes
 * through unchanged.
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
        val payload = extractFirstJsonObject(raw)
        val root = payload?.let { runCatching { JSONTokener(it).nextValue() as? JSONObject }.getOrNull() }
        return root?.let {
            val fields = SCHEMA_KEYS.associateWith { key -> normalize(it.opt(key)) }
            val flags = (normalize(it.opt("flags")) as? List<*>)
                ?.mapNotNull(::encodeFlag)
                ?: emptyList()
            LensExtraction(lens = lens, fields = fields, flags = flags)
        }
    }

    /**
     * Schema flag form is `{kind, snippet, note}` per `output-schema.txt`. The encoded string
     * is `"$kind:$snippet:$note"` with empty segments for missing sub-keys so the colon count is
     * fixed and a downstream split is unambiguous. Returns `null` for entries that have no
     * useful content (every segment empty, or a non-string/non-object value).
     */
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
     * Walk the raw response and return the first balanced JSON object as a substring. Honors
     * string literals (so a `{` inside a quoted string doesn't open a fake object) and escape
     * sequences. Returns `null` if no balanced object is found.
     */
    private fun extractFirstJsonObject(raw: String): String? {
        val start = raw.takeIf { it.isNotBlank() }?.indexOf('{')?.takeIf { it >= 0 } ?: return null
        val state = ScanState()
        var endExclusive = -1
        for (i in start until raw.length) {
            advance(state, raw[i])
            if (state.closed) {
                endExclusive = i + 1
                break
            }
        }
        return if (endExclusive > 0) raw.substring(start, endExclusive) else null
    }

    /**
     * Single-character step of the balanced-brace scanner. Mutates [state] in place; the caller
     * checks [ScanState.closed] after each step. The split keeps `extractFirstJsonObject`'s
     * cyclomatic complexity inside the project's detekt threshold.
     */
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
