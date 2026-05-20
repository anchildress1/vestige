@file:Suppress("TooManyFunctions")

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
            val evidence = obj?.optString("evidence")?.takeIf { it.isNotBlank() }
            val fields = obj?.optJSONArray("fields")
                ?.let { values ->
                    (0 until values.length()).mapNotNull { idx ->
                        values.optString(idx).takeIf(String::isNotBlank)
                    }
                }
                ?: emptyList()
            text?.let { ObservationLine(text = it, evidence = evidence, fields = fields) }
        }
    }.getOrElse {
        // Surfaced so an empty reading card is debuggable, but never the payload:
        // observation text is private journal content (no-telemetry/privacy invariant).
        Log.w("EntryDetailUiModel", "malformed entryObservationsJson (len=${json.length})")
        emptyList()
    }
}

internal fun buildLensReads(json: String?): List<LensRead> {
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

@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun buildFieldRows(entity: EntryEntity): List<FieldRow> {
    val confidence = parseConfidence(entity.confidenceJson)
    val receipts = EntryLensReceiptJson.decodeOrNull(entity.lensReceiptsJson)
    val tagsText = entity.tags.map { it.name }.sorted().take(DISPLAY_LIMIT).joinToString(", ").ifBlank { DASH }
    val topLevelState = entity.energyDescriptor?.takeIf(String::isNotBlank)
    val stateValue = topLevelState
        ?: receipts?.let { firstReceiptFieldDisplay(it, KEY_ENERGY) }
        ?: DASH
    val stateTone = when {
        receipts == null -> confidence[KEY_ENERGY].toTone()
        topLevelState != null -> confidence[KEY_ENERGY].toTone()
        stateValue != DASH -> receiptFieldTone(receipts, KEY_ENERGY)
        else -> confidence[KEY_ENERGY].toTone()
    }
    val vocabFromReceipts = receipts?.let { firstReceiptFieldDisplay(it, KEY_VOCAB) }
    val vocabFallback = receipts?.let { repeatedLexicalTerms(entity.entryText, it) }
    val vocabValue = when {
        receipts == null -> EntryDetailCopy.LENS_UNREADABLE
        vocabFromReceipts != null -> vocabFromReceipts
        vocabFallback != null -> vocabFallback
        else -> DASH
    }
    val vocabTone = when {
        receipts == null -> LensTone.CONFLICT
        vocabFromReceipts != null -> receiptFieldTone(receipts, KEY_VOCAB)
        vocabFallback != null -> receiptLexicalTone(entity.entryText, receipts)
        else -> confidence[KEY_VOCAB].toTone()
    }
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
    val topLevelRecurrence = entity.recurrenceLink?.takeIf(String::isNotBlank)
    val recurrenceValue = topLevelRecurrence
        ?: receipts?.let { firstReceiptPatternId(it) }
        ?: DASH
    val recurrenceTone = when {
        receipts == null -> confidence[KEY_RECURRENCE].toTone()
        topLevelRecurrence != null -> confidence[KEY_RECURRENCE].toTone()
        recurrenceValue != DASH -> receiptPatternTone(receipts)
        else -> LensTone.AMBIGUOUS
    }
    return listOf(
        FieldRow(
            label = "BEHAVIOR",
            value = tagsText,
            tone = confidence[KEY_TAGS].toTone(),
        ),
        FieldRow(
            label = "STATE",
            value = stateValue,
            tone = stateTone,
        ),
        FieldRow(label = "VOCAB", value = vocabValue, tone = vocabTone),
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

private fun firstReceiptFieldDisplay(receipts: List<EntryLensReceipt>, key: String): String? =
    receipts.asSequence().mapNotNull { displayValue(it.fields[key]) }.firstOrNull()

private fun firstReceiptPatternId(receipts: List<EntryLensReceipt>): String? = receipts.asSequence()
    .mapNotNull { it.fields[KEY_RECURRENCE] as? String }
    .map(String::trim)
    .firstOrNull { it.matches(PATTERN_ID_REGEX) }

private fun receiptFieldTone(receipts: List<EntryLensReceipt>, key: String): LensTone {
    val supported = receipts.count { displayValue(it.fields[key]) != null }
    return when {
        receipts.any { displayValue(it.fields[key]) != null && it.flags.isNotEmpty() } -> LensTone.CONFLICT
        supported >= 2 -> LensTone.CANONICAL
        supported == 1 -> LensTone.CANDIDATE
        else -> LensTone.AMBIGUOUS
    }
}

private fun receiptPatternTone(receipts: List<EntryLensReceipt>): LensTone {
    val supported = receipts.count {
        (it.fields[KEY_RECURRENCE] as? String)?.trim()?.matches(PATTERN_ID_REGEX) == true
    }
    return when {
        receipts.any {
            (it.fields[KEY_RECURRENCE] as? String)?.trim()?.matches(PATTERN_ID_REGEX) == true &&
                it.flags.isNotEmpty()
        } -> LensTone.CONFLICT

        supported >= 2 -> LensTone.CANONICAL

        supported == 1 -> LensTone.CANDIDATE

        else -> LensTone.AMBIGUOUS
    }
}

private fun repeatedLexicalTerms(entryText: String, receipts: List<EntryLensReceipt>): String? {
    val counts = entryText.lowercase()
        .split(WORD_SPLIT_REGEX)
        .map { it.trim() }
        .filter { it.length >= MIN_VOCAB_TERM_LENGTH && it !in VOCAB_STOP_WORDS }
        .groupingBy { it }
        .eachCount()
    if (counts.isEmpty()) return null

    val receiptSupport = receiptLexicalSupport(receipts, counts)

    val terms = receiptSupport.entries
        .asSequence()
        .filter { (_, support) -> support >= MIN_RECEIPT_SUPPORT_FOR_VOCAB }
        .sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenByDescending { counts[it.key] ?: 0 }
                .thenBy { it.key },
        )
        .map(Map.Entry<String, Int>::key)
        .take(DISPLAY_LIMIT)
        .toList()
    return terms.joinToString(", ").takeIf(String::isNotBlank)
}

private fun receiptLexicalSupport(receipts: List<EntryLensReceipt>, counts: Map<String, Int>): Map<String, Int> {
    val receiptSupport = mutableMapOf<String, Int>()
    receipts.forEach { receipt ->
        val supportedTerms = receiptSupportedTerms(receipt, counts)
        supportedTerms.forEach { term ->
            receiptSupport[term] = (receiptSupport[term] ?: 0) + 1
        }
    }
    return receiptSupport
}

private fun receiptSupportedTerms(receipt: EntryLensReceipt, counts: Map<String, Int>): Set<String> {
    val tags = (receipt.fields[KEY_TAGS] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
    return tags.asSequence()
        .flatMap { tag -> tag.lowercase().split('-').asSequence() }
        .filter { part -> part.length >= MIN_VOCAB_TERM_LENGTH && part !in VOCAB_STOP_WORDS }
        .filter { part -> (counts[part] ?: 0) > 1 }
        .toSet()
}

private fun receiptLexicalTone(entryText: String, receipts: List<EntryLensReceipt>): LensTone {
    val repeatedTerms = repeatedLexicalTerms(entryText, receipts)?.split(", ")?.toSet().orEmpty()
    if (repeatedTerms.isEmpty()) return LensTone.AMBIGUOUS
    val supported = receipts.count { receipt ->
        val tags = (receipt.fields[KEY_TAGS] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        tags.any { tag ->
            tag.lowercase()
                .split('-')
                .any { part -> part in repeatedTerms }
        }
    }
    return when {
        supported >= 2 -> LensTone.CANONICAL
        supported == 1 -> LensTone.CANDIDATE
        else -> LensTone.AMBIGUOUS
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
private val PATTERN_ID_REGEX = Regex("[0-9a-f]{64}")
private val WORD_SPLIT_REGEX = Regex("[^a-z0-9]+")
private const val MIN_VOCAB_TERM_LENGTH = 3
private const val MIN_RECEIPT_SUPPORT_FOR_VOCAB = 2
private val VOCAB_STOP_WORDS = setOf(
    "the",
    "and",
    "for",
    "with",
    "that",
    "this",
    "have",
    "from",
    "were",
    "they",
    "still",
    "after",
    "before",
    "while",
    "today",
    "again",
    "your",
    "just",
    "into",
    "even",
    "worth",
)
private val SUMMARY_KEYS = listOf(
    KEY_ENERGY,
    KEY_VOCAB,
    KEY_TAGS,
    KEY_COMMITMENT,
    KEY_RECURRENCE,
    "state_shift",
)
