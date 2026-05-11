package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.DetectedPattern
import dev.anchildress1.vestige.model.PatternKind
import org.json.JSONObject
import java.util.Locale

/**
 * Deterministic callout-text builder per ADR-003 §"Pattern primitives (v1)" examples. ADR-003
 * caps pattern-engine work at one short model call per newly-active pattern (title only); the
 * callout text is templated from the signature + supporting count so it stays sourced by
 * construction (AGENTS.md guardrail 12) and never invents interpretive language.
 *
 * Persona-flavored variants live in `:core-inference` if v1.5 brings them in; for v1 the
 * templated string already mirrors the ADR's "Witness tone" examples.
 */
internal object PatternCalloutText {

    fun build(detected: DetectedPattern): String {
        val count = detected.supportingEntryCount
        val signature = runCatching { JSONObject(detected.signatureJson) }.getOrNull()
        return when (detected.kind) {
            PatternKind.TEMPLATE_RECURRENCE -> templateRecurrence(signature, count)
            PatternKind.TAG_PAIR_CO_OCCURRENCE -> tagPair(signature, count)
            PatternKind.TIME_OF_DAY_CLUSTER -> goblin(count)
            PatternKind.COMMITMENT_RECURRENCE -> commitment(signature, count)
            PatternKind.VOCAB_FREQUENCY -> vocab(signature, count)
        }
    }

    private fun templateRecurrence(signature: JSONObject?, count: Int): String {
        val label = signature?.optString("label").orEmpty().humanize()
        return "$count $label entries logged. Worth noting."
    }

    private fun tagPair(signature: JSONObject?, count: Int): String {
        val label = signature?.optString("label").orEmpty().humanize()
        val tags = signature?.optJSONArray("tags")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
        } ?: emptyList()
        val joined = tags.joinToString(" + ")
        return "$label entries: $joined across $count entries."
    }

    private fun goblin(count: Int): String = "$count entries between midnight and 5am. Same admin loop."

    private fun commitment(signature: JSONObject?, count: Int): String {
        val topic = signature?.optString("topic_or_person").orEmpty().humanize()
        return "$count entries with a commitment about $topic."
    }

    private fun vocab(signature: JSONObject?, count: Int): String {
        val token = signature?.optString("token").orEmpty().humanize()
        return "'$token' appears across $count entries with multiple framings."
    }

    private fun String.humanize(): String {
        if (isEmpty()) return ""
        return split('-').joinToString(" ") { it.replaceFirstChar { ch -> ch.titlecase(Locale.ROOT) } }
    }
}
