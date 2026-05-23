package dev.anchildress1.vestige.patterns

import dev.anchildress1.vestige.inference.HistoryChunk
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.PatternMatcher
import java.time.ZoneId

/**
 * Builds the deterministic candidate-pattern context a lens read needs to adjudicate recurrence.
 *
 * Detection (cheap, signature-based) only proves a structural coincidence — same weekday + time
 * block, repeated template, etc. Whether that coincidence is a real recurring state ("the energy
 * is gone after every Tuesday meeting") or a mere logging artifact ("I always journal at 5pm") is a
 * judgement only the model can make from the prior entries' content. So each matched ACTIVE pattern
 * becomes one [HistoryChunk] carrying its `pattern_id` (the recurrence surface links back to it on a
 * viable match) plus the pattern's strictly-earlier supporting entries as the evidence to weigh.
 */
object PatternCandidates {

    /**
     * One chunk per ACTIVE pattern in [activePatterns] whose signature [target] matches, each with
     * the pattern's `pattern_id` and up to [maxPriorEntries] of its earlier supporting entries
     * (most recent first). Patterns with no strictly-earlier supporter are dropped — the model has
     * nothing to compare the current entry against.
     */
    fun forEntry(
        target: EntryEntity,
        activePatterns: List<PatternEntity>,
        zoneId: ZoneId,
        maxPriorEntries: Int,
    ): List<HistoryChunk> = activePatterns
        .filter { PatternMatcher.matches(target, it, zoneId) }
        .mapNotNull { pattern ->
            val priors = pattern.supportingEntries.asSequence()
                .filter { it.id != target.id && it.timestampEpochMs < target.timestampEpochMs }
                .sortedByDescending { it.timestampEpochMs }
                .take(maxPriorEntries)
                .map { it.entryText }
                .toList()
            if (priors.isEmpty()) {
                null
            } else {
                HistoryChunk(
                    patternId = pattern.patternId,
                    text = priors.joinToString("\n\n"),
                )
            }
        }
}
