package dev.anchildress1.vestige.storage

import android.util.Log
import org.json.JSONObject

/**
 * Builds the semantic embedding target for one entry from its distilled extraction output —
 * tags, observation texts, and the stated-commitment topic — instead of the raw verbatim
 * transcription body. A 30s stream-of-consciousness voice entry's centroid is noise; the
 * extracted fields are the model's own distillation of what the entry is *about*.
 *
 * Shape: `"{tags}. {observations}. {commitment topic}"`. Any empty component and its
 * separator are omitted. See architecture-brief.md §"Embedding Strategy" and Story 3.11.
 *
 * @param entity the persisted row; reads its `tags` relation, `entryObservationsJson`, and
 *   `statedCommitmentJson`.
 * @return the synthesized string, or `""` when the entry distilled nothing embeddable.
 */
fun buildEmbeddingText(entity: EntryEntity): String {
    val tags = entity.tags
        .mapNotNull { it.name.trim().takeIf(String::isNotEmpty) }
        .joinToString(" ")
    val observations = decodeObservations(entity.entryObservationsJson)
        .joinToString(". ") { it.text.trim() }
    val commitmentTopic = commitmentTopic(entity.statedCommitmentJson)
    return listOf(tags, observations, commitmentTopic)
        .filter { it.isNotBlank() }
        .joinToString(". ")
}

private fun commitmentTopic(json: String?): String {
    val raw = json?.takeIf { it.isNotBlank() } ?: return ""
    // `optString` already collapses a missing key / JSON null to "". The literal-"null" guard
    // catches the model emitting the *string* "null" as a topic — a known LLM failure mode we
    // must not embed as semantic content.
    return runCatching { JSONObject(raw).optString("topic_or_person") }
        .onFailure { Log.w(TAG, "Malformed statedCommitmentJson (${it.javaClass.simpleName}); topic excluded") }
        .getOrNull()
        ?.trim()
        ?.takeUnless { it.isEmpty() || it == "null" }
        ?: ""
}

private const val TAG = "VestigeEmbedText"
