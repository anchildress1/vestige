package dev.anchildress1.vestige.ui.history

import dev.anchildress1.vestige.storage.EntryEntity
import java.time.ZoneId

private const val SNIPPET_MAX = 140

/** Immutable UI projection of a single history row. Equality-friendly — safe as LazyColumn key. */
data class HistorySummary(
    val id: Long,
    val timestampEpochMs: Long,
    val timeLabel: String,
    val dateLabel: String,
    val templateLabel: String?,
    val snippet: String,
    val durationMs: Long,
) {
    companion object {
        fun from(entity: EntryEntity, zoneId: ZoneId): HistorySummary = HistorySummary(
            id = entity.id,
            timestampEpochMs = entity.timestampEpochMs,
            timeLabel = HistoryDateFormatter.formatClock12(entity.timestampEpochMs, zoneId),
            dateLabel = HistoryDateFormatter.formatMonthDay(entity.timestampEpochMs, zoneId),
            templateLabel = entity.templateLabel?.serial,
            snippet = entity.entryText.replace('\n', ' ').replace('\r', ' ').trim().take(SNIPPET_MAX),
            durationMs = entity.durationMs,
        )
    }
}
