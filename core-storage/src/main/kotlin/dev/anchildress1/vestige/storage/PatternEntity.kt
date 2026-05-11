package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.PatternState
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.Unique
import io.objectbox.converter.PropertyConverter
import io.objectbox.relation.ToMany

/**
 * Content-addressable pattern row per ADR-003 §"ObjectBox `Pattern` entity". [patternId] is the
 * SHA-256 of the normalized signature — re-running detection over the same data yields the same
 * key. Lifecycle transitions are enforced by [PatternStore], not here.
 */
@Suppress("LongParameterList") // Persistence shape matches the ADR-003 schema verbatim.
@Entity
class PatternEntity(
    @Id var id: Long = 0,

    /** SHA-256 hex of the normalized signature. Stable across re-detection. */
    @Index @Unique var patternId: String = "",

    @io.objectbox.annotation.Convert(converter = PatternKindConverter::class, dbType = String::class)
    var kind: PatternKind = PatternKind.TEMPLATE_RECURRENCE,

    /** Exact bytes that were hashed — debug + dedup audit. */
    var signatureJson: String = "",

    /** Model-generated, ≤24 chars. Generated on insert, never regenerated in v1. */
    var title: String = "",

    /** Denormalized template label for fast filter queries. Null for vocab / goblin kinds. */
    var templateLabel: String? = null,

    /** When threshold ≥3 was first crossed. Epoch millis UTC. */
    var firstSeenTimestamp: Long = 0,

    /** Most recent supporting entry timestamp. Epoch millis UTC. */
    var lastSeenTimestamp: Long = 0,

    @io.objectbox.annotation.Convert(converter = PatternStateConverter::class, dbType = String::class)
    var state: PatternState = PatternState.ACTIVE,

    /** Unix millis when state==SNOOZED expires. Null otherwise. */
    var snoozedUntil: Long? = null,

    /** When user last acted on this pattern. 0 = never. Epoch millis UTC. */
    var stateChangedTimestamp: Long = 0,

    /** Pre-rendered callout line; appended to per-entry observation when this pattern fires. */
    var latestCalloutText: String = "",
) {
    lateinit var supportingEntries: ToMany<EntryEntity>
}

internal class PatternKindConverter : PropertyConverter<PatternKind, String> {
    override fun convertToEntityProperty(databaseValue: String?): PatternKind =
        databaseValue?.let { PatternKind.fromSerial(it) ?: runCatching { PatternKind.valueOf(it) }.getOrNull() }
            ?: PatternKind.TEMPLATE_RECURRENCE

    override fun convertToDatabaseValue(entityProperty: PatternKind): String = entityProperty.serial
}

internal class PatternStateConverter : PropertyConverter<PatternState, String> {
    override fun convertToEntityProperty(databaseValue: String?): PatternState =
        databaseValue?.let { PatternState.fromSerial(it) ?: runCatching { PatternState.valueOf(it) }.getOrNull() }
            ?: PatternState.ACTIVE

    override fun convertToDatabaseValue(entityProperty: PatternState): String = entityProperty.serial
}
