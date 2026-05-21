package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.DetectedPattern
import dev.anchildress1.vestige.model.PatternKind
import org.json.JSONObject
import java.util.Locale

/**
 * Deterministic callout-text builder. Text is templated from signature fields and supporting-entry
 * count; it never invents language not derivable from the stored data.
 */
object PatternCalloutText {

    fun build(detected: DetectedPattern): String {
        val count = detected.supportingEntryCount
        val signature = runCatching { JSONObject(detected.signatureJson) }.getOrNull()
        if (signature == null && detected.signatureJson.isNotBlank()) {
            android.util.Log.w(
                "VestigeCalloutText",
                "malformed signatureJson for ${detected.kind.serial} (len=${detected.signatureJson.length})",
            )
        }
        return when (detected.kind) {
            PatternKind.TEMPLATE_RECURRENCE -> templateRecurrence(signature, count)
            PatternKind.TAG_PAIR_CO_OCCURRENCE -> tagPair(signature, count)
            PatternKind.TIME_OF_DAY_CLUSTER -> goblin(count)
            PatternKind.COMMITMENT_RECURRENCE -> commitment(signature, count)
            PatternKind.VOCAB_FREQUENCY -> vocab(signature, count)
            PatternKind.TEMPORAL_RELATIVE -> temporal(signature, count)
        }
    }

    private fun templateRecurrence(signature: JSONObject?, count: Int): String {
        val label = signature?.optString("label").orEmpty().humanize()
        return if (label.isBlank()) {
            "$count entries share the same resolved shape."
        } else {
            "$count $label entries share the same resolved shape."
        }
    }

    private fun tagPair(signature: JSONObject?, count: Int): String {
        val label = signature?.optString("label").orEmpty().humanize()
        val tags = signature?.optJSONArray("tags")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
        } ?: emptyList()
        if (label.isBlank() || tags.isEmpty()) {
            warnBlankSignatureField("tagPair", "label/tags", signature)
            return "$count entries share a tag pair."
        }
        return "$label entries: ${tags.joinToString(" + ")} across $count entries."
    }

    private fun goblin(count: Int): String = "$count entries landed between midnight and 5am."

    private fun commitment(signature: JSONObject?, count: Int): String {
        val topic = signature?.optString("topic_or_person").orEmpty().humanize()
        if (topic.isBlank()) {
            warnBlankSignatureField("commitment", "topic_or_person", signature)
            return "$count entries reference the same commitment."
        }
        return "$count entries with a commitment about $topic."
    }

    private fun vocab(signature: JSONObject?, count: Int): String {
        val token = signature?.optString("token").orEmpty().humanize()
        if (token.isBlank()) {
            warnBlankSignatureField("vocab", "token", signature)
            return "$count entries share a vocab token."
        }
        return "\"$token\" spans $count entries with multiple framings."
    }

    private fun warnBlankSignatureField(kind: String, field: String, signature: JSONObject?) {
        // Detector contract says every emitted signature carries the fields the callout reads.
        // A blank field at this point is upstream corruption — log id + kind + missing field so
        // the detector regression surfaces. No raw content; AGENTS.md user-data rule.
        android.util.Log.w(
            "VestigeCalloutText",
            "blank $field in $kind signature (sigKeys=${signature?.keys()?.asSequence()?.toList() ?: "null"})",
        )
    }

    private fun temporal(signature: JSONObject?, count: Int): String =
        when (TemporalRelation.fromSerial(signature?.optString("relation"))) {
            TemporalRelation.WEEKDAY_TIME_BLOCK -> {
                val day = signature?.optString("day_of_week").orEmpty().humanize()
                val block = signature?.optString("time_block").orEmpty()
                "$count $day $block entries logged. Same slot keeps showing up."
            }

            TemporalRelation.MONTH_START -> {
                "$count first-of-month entries logged. Same calendar edge keeps showing up."
            }

            null -> "$count time-relative entries logged. Same calendar slot keeps showing up."
        }

    private fun String.humanize(): String {
        if (isEmpty()) return ""
        return split('-').joinToString(" ") { it.replaceFirstChar { ch -> ch.titlecase(Locale.ROOT) } }
    }
}
