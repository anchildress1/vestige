package dev.anchildress1.vestige.ui.history

import android.util.Log
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
    val followUp: String?,
    val personaName: String,
    val energyDescriptor: String?,
    val observations: List<ObservationLine>,
    val tags: List<String>,
) {
    companion object {
        fun from(entity: EntryEntity, zoneId: ZoneId): EntryDetailUiModel = EntryDetailUiModel(
            id = entity.id,
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
    data object Loading : EntryDetailUiState
    data object NotFound : EntryDetailUiState
    data class Loaded(val model: EntryDetailUiModel) : EntryDetailUiState
}
