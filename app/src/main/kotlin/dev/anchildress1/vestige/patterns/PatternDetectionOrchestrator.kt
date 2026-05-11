package dev.anchildress1.vestige.patterns

import android.util.Log
import dev.anchildress1.vestige.inference.PatternTitleGenerator
import dev.anchildress1.vestige.model.DetectedPattern
import dev.anchildress1.vestige.model.EntryObservation
import dev.anchildress1.vestige.model.ObservationEvidence
import dev.anchildress1.vestige.model.PatternState
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.storage.CalloutCooldownStore
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.PatternCalloutText
import dev.anchildress1.vestige.storage.PatternDetector
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.PatternMatcher
import dev.anchildress1.vestige.storage.PatternStore
import io.objectbox.BoxStore
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.ZoneId

/**
 * Wiring layer called by `BackgroundExtractionSaveFlow` after `completeEntry` so the new entry
 * is already persisted with its tags + template label. Two side effects:
 *
 * 1. Every 10th entry, run [PatternDetector] + upsert results into [PatternStore]. New patterns
 *    get a model-generated title (one short call via [PatternTitleGenerator]); existing rows
 *    update their supporting set and `lastSeenTimestamp` per ADR-003 step 6.
 * 2. Select one matching active pattern for the committed entry (subject to the global 3-entry
 *    callout cooldown). When a callout fires, append a `PATTERN_CALLOUT` observation to the
 *    entry and record the firing.
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
    private val activePersonaProvider: () -> Persona = { Persona.WITNESS },
    private val clock: Clock = Clock.systemUTC(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {

    /**
     * Run after a save completes. Returns the optional [EntryObservation] the save flow should
     * append to the entry (caller wires it back with [dev.anchildress1.vestige.storage.EntryStore.appendObservation]).
     */
    suspend fun onEntryCommitted(entry: EntryEntity): EntryObservation? {
        val entryCount = boxStore.boxFor(EntryEntity::class.java).count()
        if (entryCount > 0 && entryCount % DETECTION_INTERVAL == 0L) {
            runDetection()
        }
        return selectAndRecordCallout(entry)
    }

    private suspend fun runDetection() {
        val detected = detector.detect()
        for (pattern in detected) {
            upsert(pattern)
        }
    }

    private suspend fun upsert(detected: DetectedPattern) {
        val existing = patternStore.findByPatternId(detected.patternId)
        val supportingEntries = loadSupporting(detected.supportingEntryIds)
        if (existing == null) {
            insertNewActive(detected, supportingEntries)
            return
        }
        val current = promoteSnoozedIfExpired(existing) ?: existing
        applySupportingAndCallout(current, detected, supportingEntries)
        patternStore.put(current)
    }

    private suspend fun insertNewActive(detected: DetectedPattern, supporting: List<EntryEntity>) {
        val title = titleGenerator
            .runCatching { generate(activePersonaProvider(), detected) }
            .getOrElse {
                if (it is CancellationException) throw it
                Log.w(TAG, "title generator threw ${it.javaClass.simpleName}: ${it.message}")
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
        patternStore.put(entity)
        val saved = patternStore.findByPatternId(detected.patternId) ?: return
        saved.supportingEntries.clear()
        saved.supportingEntries.addAll(supporting)
        patternStore.put(saved)
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
        // Per ADR-003 step 6: `latestCalloutText` updates on UPDATE. Titles never re-render.
        pattern.latestCalloutText = PatternCalloutText.build(detected)
    }

    private fun selectAndRecordCallout(entry: EntryEntity): EntryObservation? {
        val text = chooseCalloutText(entry)
        if (text == null) {
            cooldownStore.consumeOneEntry()
            return null
        }
        cooldownStore.recordFired(entry.id, clock.millis())
        return EntryObservation(
            text = text,
            evidence = ObservationEvidence.PATTERN_CALLOUT,
            fields = emptyList(),
        )
    }

    private fun chooseCalloutText(entry: EntryEntity): String? {
        if (!cooldownStore.isCalloutPermitted()) return null
        val candidates = patternStore.all()
            .filter { it.state == PatternState.ACTIVE && PatternMatcher.matches(entry, it, zoneId) }
        val chosen = candidates.sortedWith(
            compareByDescending<PatternEntity> { it.supportingEntries.size }
                .thenByDescending { it.lastSeenTimestamp },
        ).firstOrNull()
        return chosen?.latestCalloutText?.takeIf { it.isNotBlank() }
    }

    private fun loadSupporting(ids: List<Long>): List<EntryEntity> {
        if (ids.isEmpty()) return emptyList()
        val box = boxStore.boxFor(EntryEntity::class.java)
        return ids.mapNotNull { box.get(it) }
    }

    private fun deterministicFallbackTitle(detected: DetectedPattern): String {
        val source = detected.templateLabel ?: detected.kind.serial.replace('_', ' ')
        return source.replaceFirstChar { it.titlecase() }.take(MAX_TITLE_CHARS)
    }

    companion object {
        const val DETECTION_INTERVAL: Long = 10
        const val MAX_TITLE_CHARS: Int = 24

        private const val TAG = "VestigePatternOrch"
    }
}
