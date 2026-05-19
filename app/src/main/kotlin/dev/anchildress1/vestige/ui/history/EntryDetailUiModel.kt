package dev.anchildress1.vestige.ui.history

import android.util.Log
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.storage.EntryEntity
import org.json.JSONArray
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
    val observations: List<ObservationLine>,
    val tags: List<String>,
    /** Until the 3-lens extraction resolves the screen shows the spinner/skeleton state. */
    val extractionComplete: Boolean = true,
    val extractionFailed: Boolean = false,
) {
    companion object {
        fun from(entity: EntryEntity, zoneId: ZoneId): EntryDetailUiModel = EntryDetailUiModel(
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
            observations = parseObservations(entity.entryObservationsJson),
            tags = entity.tags.map { it.name }.sorted(),
            extractionComplete = entity.extractionStatus == ExtractionStatus.COMPLETED,
            extractionFailed = entity.extractionStatus == ExtractionStatus.FAILED ||
                entity.extractionStatus == ExtractionStatus.TIMED_OUT,
        )

        private fun parseObservations(json: String): List<ObservationLine> {
            if (json.isBlank() || json.trim() == "[]") return emptyList()
            return runCatching {
                val array = JSONArray(json)
                (0 until array.length()).mapNotNull { i ->
                    val obj = array.optJSONObject(i)
                    val text = obj?.optString("text")?.takeIf { it.isNotBlank() }
                    text?.let { ObservationLine(it) }
                }
            }.getOrElse {
                // Surfaced so an empty reading card is debuggable, but never the payload:
                // observation text is private journal content (no-telemetry/privacy invariant).
                Log.w("EntryDetailUiModel", "malformed entryObservationsJson (len=${json.length})")
                emptyList()
            }
        }
    }
}

data class ObservationLine(val text: String)

sealed interface EntryDetailUiState {
    object Loading : EntryDetailUiState
    object NotFound : EntryDetailUiState
    data class Loaded(val model: EntryDetailUiModel) : EntryDetailUiState
}
