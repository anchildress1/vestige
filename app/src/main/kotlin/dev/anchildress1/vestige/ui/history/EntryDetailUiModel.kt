package dev.anchildress1.vestige.ui.history

import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.PatternState
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.lensReceiptsJsonOrEmpty
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import java.time.ZoneId

/** Immutable UI projection for a single entry detail. */
data class EntryDetailUiModel(
    val id: Long,
    val timeOfDayLabel: String,
    val dateLabel: String,
    val filedTimeLabel: String,
    val entryNumberLabel: String,
    val audioLabel: String,
    val wordCount: Int,
    val transcription: String,
    val followUp: String?,
    val personaName: String,
    /** Trusted model-picked archetype shown in the top label slot; null when unlabelled. */
    val templateLabel: String?,
    val lensStatus: String,
    val lenses: ImmutableList<LensRead>,
    val fields: ImmutableList<FieldRow>,
    val observations: ImmutableList<ObservationLine>,
    val tags: ImmutableList<String>,
    /** One closed state — the screen can't render an extraction that is both complete and failed. */
    val extraction: ExtractionDisplay = ExtractionDisplay.COMPLETE,
) {
    companion object {
        fun from(entity: EntryEntity, zoneId: ZoneId): EntryDetailUiModel {
            val hasLensReceiptPayload = entity.lensReceiptsJsonOrEmpty != "[]"
            val status = lensStatus(entity.confidenceJson)
            return EntryDetailUiModel(
                id = entity.id,
                timeOfDayLabel = HistoryDateFormatter.formatClock12(entity.timestampEpochMs, zoneId),
                dateLabel = HistoryDateFormatter.formatFullDate(entity.timestampEpochMs, zoneId),
                filedTimeLabel = HistoryDateFormatter.formatTimeOnly(entity.timestampEpochMs, zoneId),
                entryNumberLabel = "${EntryDetailCopy.ENTRY_NUMBER_PREFIX}${entity.id}",
                audioLabel = HistoryDurationFormatter.format(entity.durationMs),
                wordCount = entity.entryText.trim().split("\\s+".toRegex()).count { it.isNotEmpty() },
                transcription = entity.entryText,
                followUp = entity.followUpText?.takeIf(String::isNotBlank),
                personaName = entity.persona.name,
                templateLabel = entity.templateLabel?.displayName,
                lensStatus = status,
                lenses = buildLensReads(
                    entity.lensReceiptsJson,
                    hasConflict = status == EntryDetailCopy.THREE_LENS_STATUS_CONFLICT,
                ).toImmutableList(),
                fields = buildFieldRows(entity, repeatPatternTitle(entity.patterns)).toImmutableList(),
                observations = parseObservations(entity.entryObservationsJson).toImmutableList(),
                tags = entity.tags.map { it.name }.sorted().toImmutableList(),
                extraction = when (entity.extractionStatus) {
                    // COMPLETED with no receipt payload → NO_READ (no lens section). A row whose
                    // receipt column predates this schema reads the same; legacy rows are dev-only
                    // (prerelease, no backward-compat) so they get no distinct state.
                    ExtractionStatus.COMPLETED ->
                        if (hasLensReceiptPayload) {
                            ExtractionDisplay.COMPLETE
                        } else {
                            ExtractionDisplay.NO_READ
                        }

                    ExtractionStatus.FAILED, ExtractionStatus.TIMED_OUT -> ExtractionDisplay.FAILED

                    ExtractionStatus.PENDING, ExtractionStatus.RUNNING -> ExtractionDisplay.IN_PROGRESS
                },
            )
        }
    }
}

/**
 * REPEAT shows the H2 title of the strongest ACTIVE pattern this entry belongs to — the same
 * deterministic title the Patterns page renders, not a model-emitted id. Strongest = most
 * supporting entries, tie-broken by most recent. Null when the entry is in no live pattern.
 */
internal fun repeatPatternTitle(patterns: List<PatternEntity>): String? = patterns
    .filter { it.state == PatternState.ACTIVE && it.title.isNotBlank() }
    .maxWithOrNull(compareBy({ it.supportingEntries.size }, { it.lastSeenTimestamp }))
    ?.title

enum class ExtractionDisplay { IN_PROGRESS, COMPLETE, FAILED, NO_READ }

enum class LensTone { CANONICAL, CONFLICT, AMBIGUOUS, CANDIDATE }

data class LensRead(val label: String, val value: String, val tone: LensTone, val rawResponse: String? = null)

data class FieldRow(val label: String, val value: String, val tone: LensTone)

data class ObservationLine(
    val text: String,
    val evidence: String? = null,
    val fields: ImmutableList<String> = persistentListOf(),
)

sealed interface EntryDetailUiState {
    object Loading : EntryDetailUiState
    object NotFound : EntryDetailUiState
    data class Loaded(val model: EntryDetailUiModel) : EntryDetailUiState
}
