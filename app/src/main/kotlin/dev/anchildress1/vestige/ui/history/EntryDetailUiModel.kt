package dev.anchildress1.vestige.ui.history

import android.util.Log
import dev.anchildress1.vestige.model.EntryObservation
import dev.anchildress1.vestige.storage.EntryEntity
import org.json.JSONArray
import java.time.ZoneId

/** Immutable UI projection for a single entry detail. */
data class EntryDetailUiModel(
    val id: Long,
    val filedTimeLabel: String,
    val entryNumberLabel: String,
    val templateLabel: String?,
    val audioLabel: String,
    val wordCount: Int,
    val transcription: String,
    val personaName: String,
    val energyDescriptor: String?,
    val observations: List<ObservationLine>,
    val tags: List<String>,
) {
    companion object {
        fun from(entity: EntryEntity, personaName: String, zoneId: ZoneId): EntryDetailUiModel = EntryDetailUiModel(
            id = entity.id,
            filedTimeLabel = HistoryDateFormatter.formatTimeOnly(entity.timestampEpochMs, zoneId),
            entryNumberLabel = "${EntryDetailCopy.ENTRY_NUMBER_PREFIX}${entity.id}",
            templateLabel = entity.templateLabel?.serial?.uppercase(),
            audioLabel = HistoryDurationFormatter.format(entity.durationMs),
            wordCount = entity.entryText.trim().split("\\s+".toRegex()).count { it.isNotEmpty() },
            transcription = entity.entryText,
            personaName = personaName,
            energyDescriptor = entity.energyDescriptor,
            observations = parseObservations(entity.entryObservationsJson),
            tags = entity.tags.map { it.name }.sorted(),
        )

        private const val LOG_PREVIEW_CHARS = 120

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
                // Malformed persisted JSON drops to no observations; surface it so an empty
                // reading card is debuggable instead of indistinguishable from a real none.
                Log.w("EntryDetailUiModel", "malformed entryObservationsJson: ${json.take(LOG_PREVIEW_CHARS)}")
                emptyList()
            }
        }
    }
}

data class ObservationLine(val text: String)

sealed interface EntryDetailUiState {
    data object Loading : EntryDetailUiState
    data object NotFound : EntryDetailUiState
    data class Loaded(val model: EntryDetailUiModel) : EntryDetailUiState
}
