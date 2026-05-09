package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.TemplateLabel
import io.objectbox.annotation.Convert
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.relation.ToMany

/**
 * Cognitive event row per `concept-locked.md` §Schema and ADR-001 §Q3. Carries the nine
 * content fields plus the operational triplet for the retry-based background-extraction
 * recovery path.
 *
 * Markdown is the source of truth (architecture-brief.md §"Sync direction & conflict policy");
 * this row is a structured cache. Phase 1 ships the schema only — Phase 2 populates fields
 * via the multi-lens extractor. The vector field is intentionally absent — it ships only if
 * STT-E passes in Phase 3 (ADR-001 §Q6).
 */
// ObjectBox @Entity carries all schema fields as constructor properties; the LongParameterList
// detekt rule doesn't fit a persistence shape that mirrors a 12-field markdown frontmatter.
@Suppress("LongParameterList")
@Entity
class EntryEntity(
    @Id var id: Long = 0,

    /** Primary, stable filename for the markdown source-of-truth file (architecture-brief.md §"Filename"). */
    @Index var markdownFilename: String = "",

    /** Substrate text — transcription or typed input. Mirrors the markdown body. */
    var entryText: String = "",

    /** UTC epoch millis. Architecture-brief specifies ISO-8601 seconds in markdown; this is the index form. */
    var timestampEpochMs: Long = 0,

    @Convert(converter = TemplateLabelConverter::class, dbType = String::class)
    var templateLabel: TemplateLabel? = null,

    /** Nullable; captured when the entry mentions a state. */
    var energyDescriptor: String? = null,

    /** Nullable; pattern_id when the entry matches a known pattern. */
    var recurrenceLink: String? = null,

    /** Nullable JSON for `{ text, topic_or_person, entry_id }` per concept-locked.md. Populated by Phase 2. */
    var statedCommitmentJson: String? = null,

    /** JSON list of `{ text, evidence, fields[] }` observation objects. Populated by Phase 2. */
    var entryObservationsJson: String = "[]",

    /** JSON object mapping field name → ConfidenceVerdict.name. Populated by the convergence resolver. */
    var confidenceJson: String = "{}",

    // Operational fields per ADR-001 §Q3.

    @Convert(converter = ExtractionStatusConverter::class, dbType = String::class)
    var extractionStatus: ExtractionStatus = ExtractionStatus.PENDING,

    /** Retry budget; cap at 3 per ADR-001 §Q3. */
    var attemptCount: Int = 0,

    /** Compact reason on failure (timeout / parse-fail / OOM / lens-error). Nullable. */
    var lastError: String? = null,
) {
    /** Tag references — many-to-many via ObjectBox relation. */
    lateinit var tags: ToMany<TagEntity>
}
