package dev.anchildress1.vestige.storage

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

internal class TemporalRelativePatternDetector(private val zoneId: ZoneId) {

    fun detect(entries: List<EntryEntity>): List<TemporalRelativeCandidate> = buildList {
        addAll(detectWeekdayTimeBlocks(entries))
        detectMonthStart(entries)?.let { add(it) }
    }

    private fun detectWeekdayTimeBlocks(entries: List<EntryEntity>): List<TemporalRelativeCandidate> {
        val bySlot = linkedMapOf<WeekdayTimeBlock, MutableList<EntryEntity>>()
        for (entry in entries) {
            val local = entry.localDateTime()
            val key = WeekdayTimeBlock(
                dayOfWeek = local.dayOfWeek.name.lowercase(Locale.ROOT),
                timeBlock = TemporalPatternRules.timeBlockForHour(local.hour),
            )
            bySlot.getOrPut(key) { mutableListOf() }.add(entry)
        }
        return bySlot
            .mapValues { it.value.toList() }
            .filter { (_, supporting) ->
                supporting.distinctLocalDates(zoneId).size >= PatternDetector.SUPPORTING_THRESHOLD
            }
            .map { (slot, supporting) ->
                TemporalRelativeCandidate(
                    signature = PatternSignature.forWeekdayTimeBlock(slot.dayOfWeek, slot.timeBlock),
                    supporting = supporting,
                )
            }
    }

    private fun detectMonthStart(entries: List<EntryEntity>): TemporalRelativeCandidate? {
        val supporting = entries.filter {
            it.localDateTime().dayOfMonth == TemporalPatternRules.MONTH_START_DAY
        }
        if (supporting.distinctYearMonths(zoneId).size < PatternDetector.SUPPORTING_THRESHOLD) return null
        return TemporalRelativeCandidate(
            signature = PatternSignature.forMonthStart(),
            supporting = supporting,
        )
    }

    private fun EntryEntity.localDateTime() = Instant.ofEpochMilli(timestampEpochMs).atZone(zoneId)
}

internal data class TemporalRelativeCandidate(val signature: Signature, val supporting: List<EntryEntity>)

private data class WeekdayTimeBlock(val dayOfWeek: String, val timeBlock: String)

private fun List<EntryEntity>.distinctLocalDates(zoneId: ZoneId): Set<LocalDate> = mapTo(linkedSetOf()) {
    Instant.ofEpochMilli(it.timestampEpochMs).atZone(zoneId).toLocalDate()
}

private fun List<EntryEntity>.distinctYearMonths(zoneId: ZoneId): Set<YearMonth> = mapTo(linkedSetOf()) {
    YearMonth.from(Instant.ofEpochMilli(it.timestampEpochMs).atZone(zoneId))
}
