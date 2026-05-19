package dev.anchildress1.vestige.ui.history

import android.util.Log
import dev.anchildress1.vestige.model.ConfidenceVerdict
import dev.anchildress1.vestige.model.EntryLensReceipt
import dev.anchildress1.vestige.model.Lens
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.EntryLensReceiptJson
import org.json.JSONArray
import org.json.JSONObject

internal fun parseObservations(json: String): List<ObservationLine> {
    if (json.isBlank() || json.trim() == "[]") return emptyList()
    return runCatching {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i)
            val text = obj?.optString("text")?.takeIf { it.isNotBlank() }
            text?.let { ObservationLine(it) }
        }
    }.getOrElse {
        // Surfaced so an empty reading card is debuggable, but never the payload:
        // observation text is private journal content (no-telemetry/privacy invariant).
        Log.w("EntryDetailUiModel", "malformed entryObservationsJson (len=${json.length})")
        emptyList()
    }
}

internal fun buildLensReads(json: String): List<LensRead> {
    val decoded = EntryLensReceiptJson.decodeOrNull(json)
        ?: return Lens.entries.map { lens ->
            LensRead(label = lens.name, value = EntryDetailCopy.LENS_UNREADABLE, tone = LensTone.CONFLICT)
        }
    val byLens = decoded.associateBy { it.lens }
    return Lens.entries.map { lens ->
        val receipt = byLens[lens]
        LensRead(
            label = lens.name,
            value = receipt?.summaryText() ?: EntryDetailCopy.LENS_MISSING,
            tone = receipt?.tone() ?: LensTone.AMBIGUOUS,
        )
    }
}

internal fun buildFieldRows(entity: EntryEntity): List<FieldRow> {
    val confidence = parseConfidence(entity.confidenceJson)
    val receipts = EntryLensReceiptJson.decodeOrNull(entity.lensReceiptsJson)
    val tagsText = entity.tags.map { it.name }.sorted().take(DISPLAY_LIMIT).joinToString(", ").ifBlank { DASH }
    val vocabValue = when {
        receipts == null -> EntryDetailCopy.LENS_UNREADABLE
        else -> receipts.asSequence().mapNotNull { displayValue(it.fields[KEY_VOCAB]) }.firstOrNull() ?: tagsText
    }
    val vocabTone = if (receipts == null) {
        LensTone.CONFLICT
    } else {
        confidence[KEY_VOCAB].toTone(confidence[KEY_TAGS].toTone())
    }
    return listOf(
        FieldRow(
            label = "BEHAVIOR",
            value = entity.templateLabel?.serial ?: tagsText,
            tone = confidence[KEY_TAGS].toTone(fallback = LensTone.CANONICAL),
        ),
        FieldRow(
            label = "STATE",
            value = entity.energyDescriptor?.takeIf(String::isNotBlank) ?: DASH,
            tone = confidence[KEY_ENERGY].toTone(),
        ),
        FieldRow(label = "VOCAB", value = vocabValue, tone = vocabTone),
        FieldRow(
            label = "PROMISES",
            value = commitmentText(entity.statedCommitmentJson) ?: DASH,
            tone = confidence[KEY_COMMITMENT].toTone(),
        ),
        FieldRow(
            label = "REPEAT",
            value = entity.recurrenceLink?.takeIf(String::isNotBlank) ?: DASH,
            tone = confidence[KEY_RECURRENCE].toTone(),
        ),
    )
}

internal fun lensStatus(confidenceJson: String): String {
    val verdicts = parseConfidence(confidenceJson).values
    return when {
        verdicts.any { it == ConfidenceVerdict.CANONICAL_WITH_CONFLICT } ->
            EntryDetailCopy.THREE_LENS_STATUS_CONFLICT

        verdicts.any { it == ConfidenceVerdict.CANONICAL } -> EntryDetailCopy.THREE_LENS_STATUS_CANONICAL

        verdicts.any { it == ConfidenceVerdict.CANDIDATE } -> EntryDetailCopy.THREE_LENS_STATUS_CANDIDATE

        else -> EntryDetailCopy.THREE_LENS_STATUS_AMBIGUOUS
    }
}

private fun parseConfidence(json: String): Map<String, ConfidenceVerdict> = runCatching {
    val obj = JSONObject(json)
    obj.keys().asSequence().mapNotNull { key ->
        runCatching { ConfidenceVerdict.valueOf(obj.getString(key)) }
            .getOrNull()
            ?.let { key to it }
    }.toMap()
}.getOrElse {
    Log.w("EntryDetailUiModel", "malformed confidenceJson (len=${json.length})")
    emptyMap()
}

private fun EntryLensReceipt.summaryText(): String = when {
    !extracted -> lastError?.takeIf(String::isNotBlank) ?: EntryDetailCopy.LENS_NO_OPINION

    else -> SUMMARY_KEYS.asSequence()
        .mapNotNull { key -> displayValue(fields[key]) }
        .firstOrNull()
        ?: EntryDetailCopy.LENS_NO_FIELDS
}

private fun EntryLensReceipt.tone(): LensTone = when {
    flags.isNotEmpty() -> LensTone.CONFLICT
    !extracted -> LensTone.AMBIGUOUS
    fields.values.any { displayValue(it) != null } -> LensTone.CANONICAL
    else -> LensTone.AMBIGUOUS
}

private fun commitmentText(json: String?): String? {
    val raw = json?.takeIf(String::isNotBlank) ?: return null
    return runCatching {
        val obj = JSONObject(raw)
        obj.optString(KEY_COMMITMENT_TEXT).takeIf(String::isNotBlank)
            ?: obj.optString(KEY_TOPIC_OR_PERSON).takeIf(String::isNotBlank)
    }.getOrElse {
        Log.w("EntryDetailUiModel", "malformed statedCommitmentJson (len=${raw.length})")
        null
    }
}

private fun displayValue(value: Any?): String? = when (value) {
    null -> null

    is Boolean -> if (value) "state shift" else null

    is String -> value.takeIf(String::isNotBlank)

    is List<*> -> value.mapNotNull(
        ::displayValue,
    ).take(DISPLAY_LIMIT).joinToString(", ").takeIf(String::isNotBlank)

    is Map<*, *> -> {
        val preferred = listOf("text", "snippet", "note", "topic_or_person")
            .firstNotNullOfOrNull { key -> displayValue(value[key]) }
        preferred ?: value.values.mapNotNull(::displayValue).take(DISPLAY_LIMIT).joinToString(", ")
            .takeIf(String::isNotBlank)
    }

    else -> value.toString().takeIf(String::isNotBlank)
}

private fun ConfidenceVerdict?.toTone(fallback: LensTone = LensTone.AMBIGUOUS): LensTone = when (this) {
    ConfidenceVerdict.CANONICAL -> LensTone.CANONICAL
    ConfidenceVerdict.CANONICAL_WITH_CONFLICT -> LensTone.CONFLICT
    ConfidenceVerdict.CANDIDATE -> LensTone.CANDIDATE
    ConfidenceVerdict.AMBIGUOUS -> LensTone.AMBIGUOUS
    null -> fallback
}

private const val DASH = "—"
private const val DISPLAY_LIMIT = 2
private const val KEY_TAGS = "tags"
private const val KEY_ENERGY = "energy_descriptor"
private const val KEY_VOCAB = "vocabulary_contradictions"
private const val KEY_COMMITMENT = "stated_commitment"
private const val KEY_RECURRENCE = "recurrence_link"
private const val KEY_COMMITMENT_TEXT = "text"
private const val KEY_TOPIC_OR_PERSON = "topic_or_person"
private val SUMMARY_KEYS = listOf(
    KEY_ENERGY,
    KEY_VOCAB,
    KEY_TAGS,
    KEY_COMMITMENT,
    KEY_RECURRENCE,
    "state_shift",
)
