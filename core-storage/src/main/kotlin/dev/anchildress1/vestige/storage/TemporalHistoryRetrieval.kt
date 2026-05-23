package dev.anchildress1.vestige.storage

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * Deterministic timestamp-only retrieval of prior entries that recur at the same temporal slot as
 * a target entry — same weekday + same time-of-day block (e.g. "Wednesday afternoon"). This is the
 * historical context an extraction read needs so the model can observe the recurrence ("after every
 * Wednesday meeting the energy is gone") rather than re-deriving it from semantic similarity. No
 * embeddings, no LLM — pure local-clock matching, so it works on the very first read of a new entry.
 */
object TemporalHistoryRetrieval {

    /**
     * Prior [candidates] sharing [target]'s weekday + time-of-day block, most-recent first, capped
     * at [limit]. The target itself and any entry at or after its timestamp are excluded so the
     * context is strictly historical.
     */
    fun matching(target: EntryEntity, candidates: List<EntryEntity>, zoneId: ZoneId, limit: Int): List<EntryEntity> {
        val targetSlot = slotOf(target.timestampEpochMs, zoneId)
        return candidates.asSequence()
            .filter { it.id != target.id && it.timestampEpochMs < target.timestampEpochMs }
            .filter { slotOf(it.timestampEpochMs, zoneId) == targetSlot }
            .sortedByDescending { it.timestampEpochMs }
            .take(limit)
            .toList()
    }

    private fun slotOf(epochMs: Long, zoneId: ZoneId): Slot {
        val local = Instant.ofEpochMilli(epochMs).atZone(zoneId)
        return Slot(local.dayOfWeek, TemporalPatternRules.timeBlockForHour(local.hour))
    }

    private data class Slot(val weekday: DayOfWeek, val timeBlock: String)
}
