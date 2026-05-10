package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.Lens
import dev.anchildress1.vestige.model.LensExtraction
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Parses a lens call's raw response into a [LensExtraction] (schema:
 * `resources/lenses/output-schema.txt`). Tolerant of surrounding prose / markdown fences by
 * extracting the first balanced `{...}` block. Returns `null` on any parse failure — the worker
 * treats that as "no opinion."
 *
 * Field values pass through as the model emitted them; missing or JSON-null keys come through
 * as `null`. Schema-shaped flag objects (`{kind, snippet, note}`) collapse to a stable
 * `"$kind:$snippet:$note"` string so equality comparisons stay deterministic.
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
