package dev.anchildress1.vestige.ui.history

import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.storage.EntryEntity
import java.time.ZoneId

/** Immutable UI projection for a single entry detail. */
data class EntryDetailUiModel(
    val id: Long,
    val timeOfDayLabel: String,
    val dateLabel: String,
    val filedTimeLabel: String,
    val entryNumberLabel: String,
    val templateLabel: String?,
    val audioLabel: String,
    val wordCount: Int,
    val transcription: String,
    val followUp: String?,
    val personaName: String,
    val energyDescriptor: String?,
    val lensStatus: String,
    val lenses: List<LensRead>,
    val fields: List<FieldRow>,
    val observations: List<ObservationLine>,
    val tags: List<String>,
    /** One closed state — the screen can't render an extraction that is both complete and failed. */
    val extraction: ExtractionDisplay = ExtractionDisplay.COMPLETE,
) {
    companion object {
        fun from(entity: EntryEntity, zoneId: ZoneId): EntryDetailUiModel {
            val lensReceipts = entity.lensReceiptsJson?.trim().orEmpty()
            val hasLensReceiptPayload = lensReceipts.isNotEmpty() && lensReceipts != "[]"
            return EntryDetailUiModel(
                id = entity.id,
                timeOfDayLabel = HistoryDateFormatter.formatClock12(entity.timestampEpochMs, zoneId),
                dateLabel = HistoryDateFormatter.formatFullDate(entity.timestampEpochMs, zoneId),
                filedTimeLabel = HistoryDateFormatter.formatTimeOnly(entity.timestampEpochMs, zoneId),
                entryNumberLabel = "${EntryDetailCopy.ENTRY_NUMBER_PREFIX}${entity.id}",
                templateLabel = entity.templateLabel?.serial?.uppercase(),
                audioLabel = HistoryDurationFormatter.format(entity.durationMs),
                wordCount = entity.entryText.trim().split("\\s+".toRegex()).count { it.isNotEmpty() },
                transcription = entity.entryText,
                followUp = entity.followUpText?.takeIf(String::isNotBlank),
                personaName = entity.persona.name,
                energyDescriptor = entity.energyDescriptor,
                lensStatus = lensStatus(entity.confidenceJson),
                lenses = buildLensReads(entity.lensReceiptsJson),
                fields = buildFieldRows(entity),
                observations = parseObservations(entity.entryObservationsJson),
                tags = entity.tags.map { it.name }.sorted(),
                extraction = when (entity.extractionStatus) {
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

enum class ExtractionDisplay { IN_PROGRESS, COMPLETE, FAILED, NO_READ }

enum class LensTone { CANONICAL, CONFLICT, AMBIGUOUS, CANDIDATE }

data class LensRead(val label: String, val value: String, val tone: LensTone)

data class FieldRow(val label: String, val value: String, val tone: LensTone)

data class ObservationLine(val text: String)

sealed interface EntryDetailUiState {
    object Loading : EntryDetailUiState
    object NotFound : EntryDetailUiState
    data class Loaded(val model: EntryDetailUiModel) : EntryDetailUiState
}
