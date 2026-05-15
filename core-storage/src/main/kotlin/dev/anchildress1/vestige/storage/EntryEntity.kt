package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.TemplateLabel
import io.objectbox.annotation.Convert
import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.VectorDistanceType
import io.objectbox.relation.ToMany

/**
 * Structured cache of one entry. Markdown is the source of truth — if this row is wiped the app
 * rebuilds from markdown.
 */
@Suppress("LongParameterList") // Persistence shape mirrors the 12-field markdown frontmatter.
@Entity
class EntryEntity(
    @Id var id: Long = 0,

    /** Primary, stable filename for the markdown source-of-truth file. */
    @Index var markdownFilename: String = "",

    /** Transcription or typed input. Mirrors the markdown body. */
    var entryText: String = "",

    /** UTC epoch millis. Markdown frontmatter persists ISO-8601 seconds; this is the index form. */
    var timestampEpochMs: Long = 0,

    @Convert(converter = TemplateLabelConverter::class, dbType = String::class)
    var templateLabel: TemplateLabel? = null,

    var energyDescriptor: String? = null,

    /** `pattern_id` when the entry matches a known pattern. */
    var recurrenceLink: String? = null,

    /** JSON `{ text, topic_or_person, entry_id }`. */
    var statedCommitmentJson: String? = null,

    /** JSON list of `{ text, evidence, fields[] }`. */
    var entryObservationsJson: String = "[]",

    /** JSON `field → ConfidenceVerdict.name`. */
    var confidenceJson: String = "{}",

    @Convert(converter = ExtractionStatusConverter::class, dbType = String::class)
    @Index
    var extractionStatus: ExtractionStatus = ExtractionStatus.PENDING,

    /** Millis of captured audio; 0 for typed entries or pre-duration rows. */
    var durationMs: Long = 0L,

    /** Retry budget; cap at 3. */
    var attemptCount: Int = 0,

    /** Compact failure reason (timeout / parse-fail / OOM / lens-error). */
    var lastError: String? = null,

    /**
     * EmbeddingGemma 300M cosine-space vector over [entryText]. Null until the backfill worker
     * (or save-time embedding) populates it. Hybrid retrieval treats a null vector as a zero
     * cosine contribution so un-embedded rows still rank on keyword/tag/recency.
     */
    @HnswIndex(dimensions = EMBEDDING_DIMENSIONS, distanceType = VectorDistanceType.COSINE)
    var vector: FloatArray? = null,
) {
    lateinit var tags: ToMany<TagEntity>

    companion object {
        const val EMBEDDING_DIMENSIONS = 768L
    }
}
