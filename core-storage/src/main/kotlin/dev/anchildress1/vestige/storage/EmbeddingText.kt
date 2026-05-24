package dev.anchildress1.vestige.storage

/**
 * Builds the embedding target for one entry: its model-emitted tone word
 * ([EntryEntity.vocabularyWord]) — the felt quality of the entry, not what it is about.
 *
 * Vocab-drift clustering ([EmbeddingClustering]) groups entries that share a *feeling* and
 * surfaces the distinct words used for it as the drift. That only works if the feeling is what
 * the vector encodes. Tags / observations / commitment describe the entry's *topic*, which
 * clusters by subject and never groups synonymous tones ("drained", "wiped", "running on empty")
 * across different topics — the exact reason no `VOCAB_FREQUENCY` cluster ever minted.
 *
 * @param entity the persisted row; reads [EntryEntity.vocabularyWord].
 * @return the trimmed, lowercased tone word, or `""` for an entry with no tone (a purely factual
 *   log). Never the literal string `"null"`: a null/blank tone yields an empty target, so the
 *   backfill worker skips embedding and the entry stays out of every feeling cluster.
 */
fun buildEmbeddingText(entity: EntryEntity): String = entity.vocabularyWord?.trim()?.lowercase().orEmpty()
