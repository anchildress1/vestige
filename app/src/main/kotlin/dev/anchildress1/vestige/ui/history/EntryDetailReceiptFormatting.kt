@file:Suppress("TooManyFunctions")

package dev.anchildress1.vestige.ui.history

import android.util.Log
import dev.anchildress1.vestige.model.ConfidenceVerdict
import dev.anchildress1.vestige.model.EntryLensReceipt
import dev.anchildress1.vestige.model.Lens
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.EntryLensReceiptJson
import kotlinx.collections.immutable.toImmutableList
import org.json.JSONArray
import org.json.JSONObject

internal fun parseObservations(json: String): List<ObservationLine> {
    if (json.isBlank() || json.trim() == "[]") return emptyList()
    return runCatching {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i)
            val text = obj?.optString("text")?.takeIf { it.isNotBlank() }
            val evidence = obj?.optString("evidence")?.takeIf { it.isNotBlank() }
            val fields = obj?.optJSONArray("fields")
                ?.let { values ->
                    (0 until values.length()).mapNotNull { idx ->
                        values.optString(idx).takeIf(String::isNotBlank)
                    }
                }
                ?: emptyList()
            text?.let { ObservationLine(text = it, evidence = evidence, fields = fields.toImmutableList()) }
        }
    }.getOrElse {
        // Surfaced so an empty reading card is debuggable, but never the payload:
        // observation text is private journal content (no-telemetry/privacy invariant).
        Log.w("EntryDetailUiModel", "malformed entryObservationsJson (len=${json.length})")
        emptyList()
    }
}

internal fun buildLensReads(json: String?, hasConflict: Boolean = false): List<LensRead> {
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
            tone = lensTone(lens, receipt, hasConflict),
            rawResponse = receipt?.rawResponse?.takeIf(String::isNotBlank),
        )
    }
}

/**
 * Skeptical reads red only when its flag produced a real conflict in convergence — a
 * CONSENSUS_WITH_CONFLICT verdict, surfaced as [hasConflict] — not when its raw tag list merely
 * differs while the lenses still agree on the resolved value. This keeps the lens colour
 * consistent with the card's CONSENSUS / CONFLICT status. Literal / Inferential carry no flags.
 */
private fun lensTone(lens: Lens, receipt: EntryLensReceipt?, hasConflict: Boolean): LensTone {
    if (receipt == null) return LensTone.AMBIGUOUS
    val skepticalConflict = lens == Lens.SKEPTICAL && hasConflict && receipt.flags.isNotEmpty()
    return if (skepticalConflict) LensTone.CONFLICT else receipt.baseTone()
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun buildFieldRows(entity: EntryEntity, repeatTitle: String?): List<FieldRow> {
    val confidence = parseConfidence(entity.confidenceJson)
    val receipts = EntryLensReceiptJson.decodeOrNull(entity.lensReceiptsJson)
    val tagsText = entity.tags.map { it.name }.sorted().take(DISPLAY_LIMIT).joinToString(", ").ifBlank { DASH }
    val topLevelCommitment = commitmentText(entity.statedCommitmentJson)
    val commitmentValue = topLevelCommitment
        ?: receipts?.let { firstReceiptFieldDisplay(it, KEY_COMMITMENT) }
        ?: DASH
    val commitmentTone = when {
        receipts == null -> confidence[KEY_COMMITMENT].toTone()
        topLevelCommitment != null -> confidence[KEY_COMMITMENT].toTone()
        commitmentValue != DASH -> receiptFieldTone(receipts, KEY_COMMITMENT)
        else -> confidence[KEY_COMMITMENT].toTone()
    }
    // REPEAT shows the H2 title of the pattern the model validated via recurrence_link (resolved by
    // the caller from the stored pattern_id). Deterministic detection only proposes the candidate;
    // the model decides viability, so a blank here means "no confirmed recurrence", not "no data".
    val recurrenceValue = repeatTitle?.takeIf(String::isNotBlank) ?: DASH
    val recurrenceTone = if (recurrenceValue == DASH) LensTone.AMBIGUOUS else LensTone.CONSENSUS
    val resolvedVocab = entity.vocabularyWord?.trim()?.takeIf { it.isNotBlank() && it.lowercase() !in NULLISH_VOCAB }
    val receiptVocab = receipts?.let(::distinctReceiptVocab).orEmpty()
    val vocabValue = resolvedVocab
        ?: receiptVocab.take(DISPLAY_LIMIT).joinToString(" / ").takeIf(String::isNotBlank)
        ?: DASH
    val vocabTone = when {
        resolvedVocab != null -> confidence[KEY_VOCABULARY].toTone()

        // Lenses named different tone words and convergence didn't resolve one — show the spread.
        receiptVocab.size > 1 -> LensTone.AMBIGUOUS

        receiptVocab.size == 1 -> confidence[KEY_VOCABULARY].toTone(fallback = LensTone.CANDIDATE)

        else -> LensTone.AMBIGUOUS
    }
    return listOf(
        FieldRow(
            label = "BEHAVIOR",
            value = tagsText,
            tone = confidence[KEY_TAGS].toTone(),
        ),
        FieldRow(
            label = "VOCAB",
            value = vocabValue,
            tone = vocabTone,
        ),
        FieldRow(
            label = "PROMISES",
            value = commitmentValue,
            tone = commitmentTone,
        ),
        FieldRow(
            label = "REPEAT",
            value = recurrenceValue,
            tone = recurrenceTone,
        ),
    )
}

internal fun lensStatus(confidenceJson: String): String {
    val verdicts = parseConfidence(confidenceJson).values
    return when {
        verdicts.any { it == ConfidenceVerdict.CONSENSUS_WITH_CONFLICT } ->
            EntryDetailCopy.THREE_LENS_STATUS_CONFLICT

        verdicts.any { it == ConfidenceVerdict.CONSENSUS } -> EntryDetailCopy.THREE_LENS_STATUS_CONSENSUS

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

private fun EntryLensReceipt.baseTone(): LensTone = when {
    !extracted -> LensTone.AMBIGUOUS
    fields.values.any { displayValue(it) != null } -> LensTone.CONSENSUS
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

private fun firstReceiptFieldDisplay(receipts: List<EntryLensReceipt>, key: String): String? =
    receipts.asSequence().mapNotNull { displayValue(it.fields[key]) }.firstOrNull()

private fun receiptFieldTone(receipts: List<EntryLensReceipt>, key: String): LensTone {
    val supported = receipts.count { displayValue(it.fields[key]) != null }
    return when {
        receipts.any { displayValue(it.fields[key]) != null && it.flags.isNotEmpty() } -> LensTone.CONFLICT
        supported >= 2 -> LensTone.CONSENSUS
        supported == 1 -> LensTone.CANDIDATE
        else -> LensTone.AMBIGUOUS
    }
}

private fun distinctReceiptVocab(receipts: List<EntryLensReceipt>): List<String> = receipts.asSequence()
    .mapNotNull { (it.fields[KEY_VOCABULARY] as? String)?.trim()?.lowercase()?.takeIf(String::isNotBlank) }
    .filter { it !in NULLISH_VOCAB }
    .distinct()
    .toList()

private fun displayValue(value: Any?): String? = when (value) {
    null -> null

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
    ConfidenceVerdict.CONSENSUS -> LensTone.CONSENSUS
    ConfidenceVerdict.CONSENSUS_WITH_CONFLICT -> LensTone.CONFLICT
    ConfidenceVerdict.CANDIDATE -> LensTone.CANDIDATE
    ConfidenceVerdict.AMBIGUOUS -> LensTone.AMBIGUOUS
    null -> fallback
}

private const val DASH = "—"
private const val DISPLAY_LIMIT = 2
private const val KEY_TAGS = "tags"
private const val KEY_COMMITMENT = "stated_commitment"
private const val KEY_RECURRENCE = "recurrence_link"
private const val KEY_VOCABULARY = "vocabulary"
private val NULLISH_VOCAB = setOf("null", "none", "n/a", "nil")
private const val KEY_COMMITMENT_TEXT = "text"
private const val KEY_TOPIC_OR_PERSON = "topic_or_person"
private val SUMMARY_KEYS = listOf(
    KEY_TAGS,
    KEY_COMMITMENT,
    KEY_RECURRENCE,
)
