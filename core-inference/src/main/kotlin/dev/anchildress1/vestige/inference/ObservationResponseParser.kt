package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.EntryObservation
import dev.anchildress1.vestige.model.ObservationEvidence
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Parses the observation-generator model output (schema:
 * `resources/observations/output-schema.txt`) into a list of [EntryObservation]. Tolerant of
 * surrounding prose / markdown fences — locates the first balanced `{...}` block that parses
 * as a JSON object with an `observations` array, the same shape as [LensResponseParser].
 * Returns `null` on any parse failure or schema violation; the caller treats null as "no
 * model-generated observations" and falls back to deterministic / empty.
 *
 * Validation:
 *
 * - Up to 2 observations retained; extras truncated.
 * - Each observation must carry a non-blank `text` and a recognized `evidence` value (other
 *   than `pattern-callout`, which is owned by the pattern engine, not this call).
 * - `text` is scanned for forbidden phrases per `concept-locked.md` §"Voice rules" — any match
 *   drops the entire response (returns `null`). One retry is the caller's responsibility; this
 *   parser is single-pass.
 */
@Suppress("ReturnCount") // Guard-style early-returns are clearer than nested when/let chains here.
internal object ObservationResponseParser {

    /** Lowercase phrases that mark a banned opening per the voice rules. Match is substring-based. */
    val FORBIDDEN_PHRASES: List<String> = listOf(
        "you might be feeling",
        "you might feel",
        "it seems you're",
        "it seems like you're",
        "this could indicate",
        "this might indicate",
        "i sense that",
        "perhaps you're",
        "perhaps you are",
        "it sounds like you're feeling",
        "you may want to consider",
        "you should",
    )

    private const val MAX_OBSERVATIONS = 2

    fun parse(raw: String): List<EntryObservation>? {
        val root = findFirstParseableObject(raw) ?: return null
        val array = root.optJSONArray("observations") ?: return null
        if (array.length() == 0) return null

        val accepted = mutableListOf<EntryObservation>()
        for (idx in 0 until array.length()) {
            if (accepted.size >= MAX_OBSERVATIONS) break
            val observation = parseOne(array.opt(idx)) ?: return null
            if (containsForbiddenPhrase(observation.text)) return null
            accepted += observation
        }
        return accepted.takeIf { it.isNotEmpty() }
    }

    fun containsForbiddenPhrase(text: String): Boolean {
        val lower = text.lowercase()
        return FORBIDDEN_PHRASES.any { phrase -> lower.contains(phrase) }
    }

    private fun parseOne(node: Any?): EntryObservation? {
        val obj = node as? JSONObject ?: return null
        val text = (obj.opt("text") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val evidenceSerial = (obj.opt("evidence") as? String) ?: return null
        val evidence = ObservationEvidence.fromSerial(evidenceSerial) ?: return null
        if (evidence == ObservationEvidence.PATTERN_CALLOUT) return null

        val fields = (obj.opt("fields") as? JSONArray)?.let { arr ->
            (0 until arr.length()).mapNotNull { idx -> (arr.opt(idx) as? String)?.trim()?.takeIf { it.isNotEmpty() } }
        } ?: emptyList()

        return EntryObservation(text = text, evidence = evidence, fields = fields)
    }

    private fun findFirstParseableObject(raw: String): JSONObject? {
        if (raw.isBlank()) return null
        var cursor = 0
        while (cursor < raw.length) {
            val open = raw.indexOf('{', cursor).takeIf { it >= 0 } ?: return null
            val close = scanBalancedClose(raw, open) ?: return null
            val candidate = raw.substring(open, close + 1)
            val parsed = runCatching { JSONTokener(candidate).nextValue() as? JSONObject }.getOrNull()
            if (parsed != null) return parsed
            cursor = open + 1
        }
        return null
    }

    private fun scanBalancedClose(raw: String, open: Int): Int? {
        var depth = 0
        var inString = false
        var escape = false
        for (i in open until raw.length) {
            val c = raw[i]
            if (escape) {
                escape = false
                continue
            }
            when {
                c == '\\' && inString -> escape = true

                c == '"' -> inString = !inString

                !inString && c == '{' -> depth++

                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }
}
