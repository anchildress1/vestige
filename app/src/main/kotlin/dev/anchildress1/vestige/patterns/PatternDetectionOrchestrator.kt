package dev.anchildress1.vestige.patterns

import android.util.Log
import dev.anchildress1.vestige.inference.PatternTitleGenerator
import dev.anchildress1.vestige.model.DetectedPattern
import dev.anchildress1.vestige.model.EntryObservation
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.ObservationEvidence
import dev.anchildress1.vestige.model.PatternState
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.storage.CalloutCooldownStore
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.EntryEntity_
import dev.anchildress1.vestige.storage.PatternCalloutText
import dev.anchildress1.vestige.storage.PatternDetector
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.PatternMatcher
import dev.anchildress1.vestige.storage.PatternStore
import io.objectbox.BoxStore
import io.objectbox.query.QueryBuilder
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.ZoneId

/**
 * Wiring layer called by `BackgroundExtractionSaveFlow` after `completeEntry`. Two side effects:
 *
 * 1. Once the database has at least [PATTERN_SURFACE_MIN_ENTRIES] completed entries, run
 *    [PatternDetector] + upsert results into [PatternStore] on every committed entry.
 * 2. Select one matching active pattern for the committed entry, filtered by per-pattern callout
 *    cooldown. When a callout fires, append a `PATTERN_CALLOUT` observation and record the firing
 *    on that pattern's cooldown row. Every committed entry decrements every other pattern's
 *    active cooldown counter by one.
 *
 * The orchestrator is best-effort — any failure inside it must not propagate to the save flow.
 * Callers wrap the call in a try/catch; this class surfaces failures via [Log] only.
 */
@Suppress("LongParameterList") // Constructor-injection seams across storage + inference modules.
class PatternDetectionOrchestrator(
    private val boxStore: BoxStore,
    private val detector: PatternDetector,
    private val patternStore: PatternStore,
    private val titleGenerator: PatternTitleGenerator,
    private val cooldownStore: CalloutCooldownStore,
    private val clock: Clock = Clock.systemUTC(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val patternSurfaceMinEntries: Long = PATTERN_SURFACE_MIN_ENTRIES,
) {

    /**
     * Compute the optional callout for [entry] under the given [persona]. A returned observation
     * means [entry] holds a callout reservation against the chosen pattern's cooldown row; the
     * save flow must either confirm it after persistence or release it when append fails.
     *
     * Selection now matches before reserving: the matcher filters candidates by per-pattern
     * cooldown eligibility (ADR-016) and picks the strongest survivor. With no eligible match,
     * we still decrement every active cooldown so suppressed patterns count down on schedule.
     *
     * [persona] is per-call (not constructor-pinned) so the same orchestrator instance can
     * serve every session — capture sessions own persona, the orchestrator is process-scoped.
     */
    // Early returns reflect three distinct failure paths (no match / reservation blocked /
    // blank callout text), each with its own cleanup. A single-exit form would tangle them.
    @Suppress("ReturnCount")
    suspend fun onEntryCommitted(entry: EntryEntity, persona: Persona): EntryObservation? {
        val entryCount = completedEntryCount(boxStore)
        if (entryCount >= patternSurfaceMinEntries) {
            runDetection(persona)
        }
        val matched = chooseMatchingPattern(entry)
        if (matched == null) {
            cooldownStore.decrementAllActive()
            return null
        }
        val outcome = cooldownStore.tryReserveCallout(entry.id, matched.patternId)
        if (outcome != CalloutCooldownStore.ReservationOutcome.RESERVED) {
            cooldownStore.decrementAllActive()
            return null
        }
        val observation = buildCalloutObservation(matched, entry.id)
        if (observation == null) {
            cooldownStore.releaseReservedCallout(entry.id)
            cooldownStore.decrementAllActive()
        }
        return observation
    }

    /**
     * Finalize a pending reservation. `fired = true` starts the matched pattern's suppression
     * window; `fired = false` drops the reservation. Either way, all other patterns' active
     * counters decrement once for this committed entry.
     */
    fun settleReservedCallout(entry: EntryEntity, fired: Boolean) {
        if (fired) {
            val firedPatternId = cooldownStore.confirmReservedCallout(entry.id, clock.millis())
            if (firedPatternId == null) {
                // Stale or duplicate settle — the reservation either never landed or another path
                // already settled it. Decrement everything (no pattern is excepted).
                Log.e(TAG, "settle(fired=true) for entry id=${entry.id} found no pending reservation")
                cooldownStore.decrementAllActive()
            } else {
                cooldownStore.decrementAllActive(exceptPatternId = firedPatternId)
            }
            return
        }
        val released = cooldownStore.releaseReservedCallout(entry.id)
        if (!released) {
            // Caller contract: settle(fired=false) follows a successful onEntryCommitted that
            // already reserved. Missing pending here is an invariant break, not a normal path.
            Log.e(TAG, "settle(fired=false) for entry id=${entry.id} found no pending reservation")
        }
        cooldownStore.decrementAllActive()
    }

    private suspend fun runDetection(persona: Persona) {
        val detected = detector.detect()
        for (pattern in detected) {
            upsert(pattern, persona)
        }
    }

    private suspend fun upsert(detected: DetectedPattern, persona: Persona) {
        val existing = patternStore.findByPatternId(detected.patternId)
        val supportingEntries = loadSupporting(detected.supportingEntryIds)
        if (existing == null) {
            insertNewActive(detected, supportingEntries, persona)
            return
        }
        val current = promoteSnoozedIfExpired(existing) ?: existing
        // Wrap the read-modify-write of supportingEntries in a tx so concurrent save calls
        // can't lose-update each other's evidence sets. ObjectBox tx is read-write-isolated.
        boxStore.runInTx {
            applySupportingAndCallout(current, detected, supportingEntries)
            patternStore.put(current)
        }
    }

    private suspend fun insertNewActive(detected: DetectedPattern, supporting: List<EntryEntity>, persona: Persona) {
        val title = titleGenerator
            .runCatching { generate(persona, detected) }
            .getOrElse {
                if (it is CancellationException) throw it
                Log.w(TAG, "title generator threw ${it.javaClass.simpleName}", it)
                null
            }
            ?: deterministicFallbackTitle(detected)
        val callout = PatternCalloutText.build(detected)
        val now = clock.millis()
        val entity = PatternEntity(
            patternId = detected.patternId,
            kind = detected.kind,
            signatureJson = detected.signatureJson,
            title = title,
            templateLabel = detected.templateLabel,
            firstSeenTimestamp = detected.firstSeenTimestamp,
            lastSeenTimestamp = detected.lastSeenTimestamp,
            state = PatternState.ACTIVE,
            stateChangedTimestamp = now,
            latestCalloutText = callout,
        )
        // Tx wraps insert + supporting-relation attach so a concurrent save can't see the row
        // mid-state (insert visible but evidence set still empty).
        boxStore.runInTx {
            patternStore.put(entity)
            val saved = patternStore.findByPatternId(detected.patternId) ?: return@runInTx
            saved.supportingEntries.clear()
            saved.supportingEntries.addAll(supporting)
            patternStore.put(saved)
        }
    }

    private fun promoteSnoozedIfExpired(pattern: PatternEntity): PatternEntity? {
        val expired = pattern.state == PatternState.SNOOZED &&
            pattern.snoozedUntil != null &&
            clock.millis() >= pattern.snoozedUntil!!
        // Route through the validator chokepoint — ADR-003 §"Auto-promotion of snoozed → active"
        // is an explicit transition and must be auditable via `PatternStore.transitionState`.
        return if (expired) patternStore.transitionState(pattern.patternId, PatternState.ACTIVE) else null
    }

    private fun applySupportingAndCallout(
        pattern: PatternEntity,
        detected: DetectedPattern,
        supporting: List<EntryEntity>,
    ) {
        pattern.lastSeenTimestamp = detected.lastSeenTimestamp
        pattern.supportingEntries.clear()
        pattern.supportingEntries.addAll(supporting)
        // ADR-003 step 6: `latestCalloutText` updates on the ACTIVE branch only. The silent-update
        // branches (snoozed within window, dismissed, resolved) accumulate supporting entries but
        // freeze the callout the user last saw — re-surfacing in v1.5 must show that string,
        // not arbitrary drift from later evidence.
        if (pattern.state == PatternState.ACTIVE) {
            pattern.latestCalloutText = PatternCalloutText.build(detected)
        }
    }

    /**
     * Pure: builds the observation for an already-chosen [matched] pattern. A blank stored
     * callout text means an upstream write path skipped it — log and return null so the caller
     * releases the reservation, never persists an empty callout. ADR-003 §"Pattern primitives"
     * requires every primitive to ship a templated callout via `PatternCalloutText.build`.
     */
    private fun buildCalloutObservation(matched: PatternEntity, entryId: Long): EntryObservation? {
        val text = matched.latestCalloutText
        return when {
            text.isBlank() -> {
                Log.w(TAG, "active pattern ${matched.patternId} has blank latestCalloutText (entry id=$entryId)")
                null
            }

            else -> EntryObservation(
                text = text,
                evidence = ObservationEvidence.PATTERN_CALLOUT,
                fields = emptyList(),
            )
        }
    }

    private fun chooseMatchingPattern(entry: EntryEntity): PatternEntity? {
        // Indexed ACTIVE-only query avoids the full-table scan on every committed entry — at
        // 100+ patterns this is the difference between "fine" and "the save-flow hot path is
        // O(n)" on the reference device. Cooldown eligibility is fetched once (bulk Set) instead
        // of one ObjectBox read per candidate — selection stays O(active patterns), not O(n²).
        val ineligible = cooldownStore.ineligiblePatternIds()
        val candidates = patternStore.findActive()
            .filter { PatternMatcher.matches(entry, it, zoneId) }
            .filter { it.patternId !in ineligible }
        return candidates.sortedWith(
            compareByDescending<PatternEntity> { it.supportingEntries.size }
                .thenByDescending { it.lastSeenTimestamp },
        ).firstOrNull()
    }

    private fun loadSupporting(ids: List<Long>): List<EntryEntity> {
        if (ids.isEmpty()) return emptyList()
        val box = boxStore.boxFor(EntryEntity::class.java)
        return ids.mapNotNull { box.get(it) }
    }

    companion object {
        /** Phase 3 threshold: detection attempts once the database holds at least this many
         * COMPLETED entries. Below this the pattern engine has no quorum to surface anything. */
        const val PATTERN_SURFACE_MIN_ENTRIES: Long = 10
        const val MAX_TITLE_CHARS: Int = 24

        private const val TAG = "VestigePatternOrch"
    }
}

private fun completedEntryCount(boxStore: BoxStore): Long = boxStore.boxFor(EntryEntity::class.java)
    .query()
    .equal(EntryEntity_.extractionStatus, ExtractionStatus.COMPLETED.name, QueryBuilder.StringOrder.CASE_SENSITIVE)
    .build()
    .use { it.count() }

private fun deterministicFallbackTitle(detected: DetectedPattern): String {
    val source = detected.templateLabel ?: detected.kind.serial.replace('_', ' ')
    return source.replaceFirstChar { it.titlecase() }
        .take(PatternDetectionOrchestrator.MAX_TITLE_CHARS)
}
