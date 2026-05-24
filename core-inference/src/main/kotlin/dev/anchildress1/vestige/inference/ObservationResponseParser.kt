package dev.anchildress1.vestige.inference

import android.util.Log
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
 *
 * Salvage-first: a malformed or voice-rule-violating *entry* is dropped, never the whole batch.
 * `null` is returned only when nothing usable survives — no parseable JSON object, no
 * `observations` array, or every entry failed. The caller treats null as "no model-generated
 * observations".
 *
 * Validation:
 *
 * - Up to 2 surviving observations retained; extras truncated.
 * - Each observation must carry a non-blank `text` and a recognized `evidence` value (other
 *   than `pattern-callout`, which is owned by the pattern engine, not this call).
 * - `text` is scanned for forbidden phrases per `concept-locked.md` §"Voice rules"; a match
 *   drops that one observation. The retry loop is the caller's responsibility; single-pass here.
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

    private const val TAG = "VestigeObservationParse"
    private const val MAX_OBSERVATIONS = 2

    // The only field entries we discard are literal nullish junk — everything else the model
    // surfaces is kept verbatim. No pre-known field whitelist: observations are model reads.
    private val NULLISH_FIELD_TOKENS = setOf("null", "none", "n/a", "nil")

    fun parse(raw: String): List<EntryObservation>? {
        val root = findFirstParseableObject(raw) ?: return reject("no-json-object")
        val array = root.optJSONArray("observations") ?: return reject("no-observations-array")
        if (array.length() == 0) return reject("empty-observations-array")

        // Salvage: keep every observation that parses and passes the voice rules; drop only the
        // bad ones. A response is rejected wholesale solely when nothing usable survives.
        val accepted = (0 until array.length())
            .asSequence()
            .mapNotNull { idx -> acceptOne(array.opt(idx)) }
            .take(MAX_OBSERVATIONS)
            .toList()
        return accepted.takeIf { it.isNotEmpty() } ?: reject("no-usable-observations")
    }

    private fun acceptOne(node: Any?): EntryObservation? {
        val observation = parseOne(node) ?: return null // parseOne logged the skip reason
        return if (containsForbiddenPhrase(observation.text)) skip("forbidden-phrase") else observation
    }

    // Privacy-safe logging: category + schema values (evidence serial / field names) only — never
    // the observation text, which is journal-derived content. [reject] = whole response unusable;
    // [skip] = one observation dropped while the rest are still considered.
    private fun <T> reject(reason: String): T? {
        Log.w(TAG, "observation response rejected: $reason")
        return null
    }

    private fun skip(reason: String): EntryObservation? {
        Log.w(TAG, "observation skipped: $reason")
        return null
    }

    fun containsForbiddenPhrase(text: String): Boolean {
        val lower = text.lowercase()
        return FORBIDDEN_PHRASES.any { phrase -> lower.contains(phrase) }
    }

    private fun parseOne(node: Any?): EntryObservation? {
        val obj = node as? JSONObject ?: return skip("non-object-entry")
        val text = (obj.opt("text") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return skip("missing-text")
        val evidenceSerial = (obj.opt("evidence") as? String) ?: return skip("missing-evidence")
        val evidence = ObservationEvidence.fromSerial(evidenceSerial)
            // Don't log the raw serial — model output is untrusted and could carry journal text.
            // Length alone is enough to spot a hallucinated blob vs a typo'd token.
            ?: return skip("unknown-evidence:len=${evidenceSerial.length}")
        if (evidence == ObservationEvidence.PATTERN_CALLOUT) return skip("pattern-callout-from-model")

        // Keep the model's field provenance verbatim — it should surface unique/relevant
        // references, not match a pre-known whitelist. Drop only blanks and literal nullish junk.
        val fields = (obj.opt("fields") as? JSONArray)?.let { arr ->
            (0 until arr.length()).mapNotNull { idx ->
                (arr.opt(idx) as? String)?.trim()
                    ?.takeIf { it.isNotEmpty() && it.lowercase() !in NULLISH_FIELD_TOKENS }
            }
        } ?: emptyList()

        return EntryObservation(text = text, evidence = evidence, fields = fields)
    }

    private fun findFirstParseableObject(raw: String): JSONObject? {
        if (raw.isBlank()) return null
        var cursor = 0
        while (cursor < raw.length) {
            val open = raw.indexOf('{', cursor).takeIf { it >= 0 } ?: return null
            val close = scanBalancedClose(raw, open)
            // Unbalanced first `{` (e.g. stray prose-brace or unclosed code fence) must not
            // abort the search — advance past it and look for the next candidate, not return
            // null, which would drop a balanced object appearing later in the string.
            if (close == null) {
                cursor = open + 1
                continue
            }
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
