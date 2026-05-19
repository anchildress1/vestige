package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.Lens
import dev.anchildress1.vestige.model.LensExtraction
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Parses a lens call's raw response into a [LensExtraction] (schema:
 * `resources/lenses/output-schema.txt`). Tolerant of surrounding prose / markdown fences — walks
 * every balanced `{...}` block, repairs narrow Gemma JSON drift, and returns the first
 * schema-shaped object. Returns `null` on any parse failure — the worker treats that as "no
 * opinion."
 *
 * `tags` are normalized at parse time (trim + lowercase, empty strings dropped) so a "Standup"
 * from one lens equals a "standup" from another at convergence-time string comparison.
 *
 * Schema-shaped flag objects (`{kind, snippet, note}`) collapse to a stable
 * `"$kind:$snippet:$note"` string. Only Skeptical lens output keeps its flags; Literal /
 * Inferential `flags` are dropped — the schema makes this single-lens contract explicit and
 * propagating drift would corrupt convergence.
 */
@Suppress("TooManyFunctions") // Small parsing helpers keep the repair path narrow and testable.
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
    private val PAYLOAD_KEYS: Set<String> = SCHEMA_KEYS + "flags"

    fun parse(lens: Lens, raw: String): LensExtraction? {
        val root = findFirstSchemaObject(raw) ?: return null
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
     * Walk forward, find every balanced `{...}` block, and return the first schema-shaped
     * JSONObject. This skips parseable-but-wrong prose objects (`{"kind":"x"}`), unwraps common
     * model envelopes (`{"result": {...schema...}}`), and accepts one-object array wrapping by
     * treating the inner object as the candidate.
     */
    private fun findFirstSchemaObject(raw: String): JSONObject? {
        var found: JSONObject? = null
        if (raw.isNotBlank()) {
            found = findFirstSchemaObjectIn(raw)
            if (found == null) {
                found = repairDanglingArrayStringItems(raw)?.let(::findFirstSchemaObjectIn)
            }
        }
        return found
    }

    private fun findFirstSchemaObjectIn(raw: String): JSONObject? {
        var cursor = 0
        var found: JSONObject? = null
        var keepScanning = true
        while (keepScanning && found == null) {
            val open = raw.indexOf('{', cursor).takeIf { it >= 0 }
            val close = open?.let { scanBalancedClose(raw, it) ?: fallbackObjectClose(raw, it) }
            if (open == null || close == null) {
                keepScanning = false
            } else {
                val candidate = raw.substring(open, close + 1)
                found = parseCandidateSchemaObject(candidate)
                cursor = open + 1
            }
        }
        return found
    }

    private fun fallbackObjectClose(raw: String, openIdx: Int): Int? = raw.lastIndexOf('}').takeIf { it > openIdx }

    private fun parseCandidateSchemaObject(candidate: String): JSONObject? {
        candidateVariants(candidate.trim()).forEach { variant ->
            parseJSONObject(variant)?.let(::selectSchemaObject)?.let { return it }
        }
        return null
    }

    private fun candidateVariants(candidate: String): List<String> {
        val variants = linkedSetOf(candidate)
        listOf<(String) -> String?>(
            ::repairDuplicateCommas,
            ::repairMissingObjectFieldCommas,
            ::repairTrailingCommas,
            ::repairUnquotedPayloadKeys,
            ::repairCurlyDoubleQuotes,
            ::repairMalformedQuotedStrings,
        ).forEach { repair ->
            variants.toList().forEach { current ->
                repair(current)?.let(variants::add)
            }
        }
        return variants.toList()
    }

    private fun parseJSONObject(candidate: String): JSONObject? =
        runCatching { JSONTokener(candidate).nextValue() as? JSONObject }.getOrNull()

    private fun selectSchemaObject(candidate: JSONObject): JSONObject? {
        if (candidate.hasAnyPayloadKey()) return candidate
        var found: JSONObject? = null
        candidate.keys().asSequence().forEach { key ->
            (candidate.opt(key) as? JSONObject)?.let { child ->
                if (found == null) {
                    found = selectSchemaObject(child)
                }
            }
        }
        return found
    }

    private fun JSONObject.hasAnyPayloadKey(): Boolean = PAYLOAD_KEYS.any(::has)

    /**
     * LiteRT occasionally drops commas between top-level object fields while still emitting the
     * full schema in order, e.g. `"state_shift": true\n"vocabulary_contradictions": [...]`.
     * Repair only the narrow "value directly followed by the next quoted key" shape so genuine
     * non-JSON garbage still fails closed.
     */
    private fun repairMissingObjectFieldCommas(candidate: String): String? {
        val repaired = MISSING_FIELD_COMMA.replace(candidate) { match ->
            "${match.groupValues[1]},${match.groupValues[2]}"
        }
        return repaired.takeIf { it != candidate }
    }

    private fun repairDuplicateCommas(candidate: String): String? {
        val repaired = LEADING_DUPLICATE_COMMA
            .replace(candidate) { match -> match.groupValues[1] }
            .let { DUPLICATE_COMMA.replace(it, ",") }
        return repaired.takeIf { it != candidate }
    }

    private fun repairTrailingCommas(candidate: String): String? {
        val repaired = TRAILING_COMMA.replace(candidate, "$1")
        return repaired.takeIf { it != candidate }
    }

    private fun repairUnquotedPayloadKeys(candidate: String): String? {
        val repaired = UNQUOTED_PAYLOAD_KEY.replace(candidate) { match ->
            "${match.groupValues[1]}\"${match.groupValues[2]}\":"
        }
        return repaired.takeIf { it != candidate }
    }

    private fun repairCurlyDoubleQuotes(candidate: String): String? {
        val repaired = candidate
            .replace('“', '"')
            .replace('”', '"')
        return repaired.takeIf { it != candidate }
    }

    // Character-level repair scanner — the branches are the spec, splitting them up obscures
    // the malformed-quote shapes this is documenting against real-device model output.
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ComplexCondition")
    private fun repairMalformedQuotedStrings(candidate: String): String? {
        val repaired = buildString(candidate.length + 8) {
            var inString = false
            var escape = false
            var stringStartIndex = -1
            var stringSawWhitespace = false
            var insertedLeadingContentQuote = false
            var index = 0
            while (index < candidate.length) {
                val c = candidate[index]
                when {
                    escape -> {
                        append(c)
                        escape = false
                    }

                    c == '\\' -> {
                        append(c)
                        if (inString) {
                            escape = true
                        }
                    }

                    c == '"' -> {
                        if (!inString) {
                            append(c)
                            inString = true
                            stringStartIndex = length
                            stringSawWhitespace = false
                            insertedLeadingContentQuote = false
                        } else {
                            val next = nextNonWhitespaceChar(candidate, index + 1)
                            if (next == null || next in STRING_CLOSE_FOLLOWERS) {
                                append(c)
                                inString = false
                                stringStartIndex = -1
                            } else {
                                if (!insertedLeadingContentQuote &&
                                    !stringSawWhitespace &&
                                    stringStartIndex >= 0 &&
                                    next.isLetter()
                                ) {
                                    insert(stringStartIndex, "\\\"")
                                    insertedLeadingContentQuote = true
                                }
                                append("\\\"")
                            }
                        }
                    }

                    (c == '\n' || c == '\r') && inString -> {
                        val next = nextNonWhitespaceChar(candidate, index + 1)
                        if (next != null && next in VALUE_CLOSE_FOLLOWERS) {
                            append('"')
                            append(c)
                            inString = false
                        } else {
                            if (c == '\r' && candidate.getOrNull(index + 1) == '\n') {
                                append("\\n")
                                index += 1
                            } else {
                                append("\\n")
                            }
                        }
                    }

                    else -> {
                        append(c)
                        if (inString && c.isWhitespace()) {
                            stringSawWhitespace = true
                        }
                    }
                }
                index += 1
            }
            if (inString) {
                append('"')
            }
        }
        return repaired.takeIf { it != candidate }
    }

    private fun nextNonWhitespaceChar(candidate: String, startIndex: Int): Char? {
        var index = startIndex
        while (index < candidate.length) {
            val c = candidate[index]
            if (!c.isWhitespace()) return c
            index += 1
        }
        return null
    }

    private fun repairDanglingArrayStringItems(raw: String): String? {
        val repaired = DANGLING_ARRAY_STRING_ITEM.replace(raw, "")
        return repaired.takeIf { it != raw }
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
        is String -> normalizeStringValue(value)
        else -> value
    }

    private fun normalizeStringValue(value: String): String = value.trim().replace(LEADING_DOUBLE_QUOTE_RUN, "\"")

    // Group 1 is only the value's final char — the match span is replaced in place, so the
    // preceding bytes are untouched and a single terminator (string/number/bool/null close,
    // or `]`/`}`) reconstructs the comma identically without re-tokenizing the whole value.
    // Over-broad matches re-parse-fail downstream, preserving the fail-closed contract.
    private val MISSING_FIELD_COMMA = Regex(
        """(["\]}\w])\s*("(?:\\.|[^"\\])+":)""",
    )

    private val DANGLING_ARRAY_STRING_ITEM = Regex(
        """,\s*"\s*(?=])""",
    )

    private val LEADING_DUPLICATE_COMMA = Regex(
        """([{\[])\s*,+""",
    )

    private val DUPLICATE_COMMA = Regex(
        """,\s*,+""",
    )

    private val TRAILING_COMMA = Regex(
        """,\s*([}\]])""",
    )

    private val UNQUOTED_PAYLOAD_KEY = Regex(
        """([{\s,])(${PAYLOAD_KEYS.joinToString("|")}):""",
    )

    private val LEADING_DOUBLE_QUOTE_RUN = Regex(
        """^""+""",
    )

    private val STRING_CLOSE_FOLLOWERS = setOf(':', ',', '}', ']')

    private val VALUE_CLOSE_FOLLOWERS = setOf(',', '}', ']')
}
