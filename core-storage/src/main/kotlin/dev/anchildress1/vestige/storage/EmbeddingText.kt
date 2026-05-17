package dev.anchildress1.vestige.storage

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
    val commitmentTopic = readCommitmentTopic(entity.statedCommitmentJson).orEmpty()
    return listOf(tags, observations, commitmentTopic)
        .filter { it.isNotBlank() }
        .joinToString(". ")
}
