package dev.anchildress1.vestige.patterns

import dev.anchildress1.vestige.inference.LiteRtLmEngine
import dev.anchildress1.vestige.inference.PatternTitleGenerator
import dev.anchildress1.vestige.model.DetectedPattern
import dev.anchildress1.vestige.model.EntryObservation
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.ObservationEvidence
import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.PatternState
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.model.TemplateLabel
import dev.anchildress1.vestige.storage.CalloutCooldownStore
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.PatternDetector
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.PatternStore
import dev.anchildress1.vestige.storage.TagEntity
import dev.anchildress1.vestige.storage.closeAfterCleaningThreadResources
import dev.anchildress1.vestige.testing.newInMemoryObjectBoxDirectory
import dev.anchildress1.vestige.testing.openInMemoryBoxStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.objectbox.BoxStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

// Single coherent suite: detection cadence, callout selection, cooldown semantics, per-pattern
// isolation. Splitting would scatter the orchestrator contract without reducing surface area.
@Suppress("LargeClass")
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class PatternDetectionOrchestratorTest {

    private lateinit var boxStore: BoxStore
    private lateinit var dataDir: File
    private lateinit var patternStore: PatternStore
    private lateinit var cooldownStore: CalloutCooldownStore
    private lateinit var orchestrator: PatternDetectionOrchestrator
    private val engine: LiteRtLmEngine = mockk()
    private val now: Instant = Instant.parse("2026-05-11T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    @Before
    fun setUp() {
        dataDir = newInMemoryObjectBoxDirectory("objectbox-orch-")
        boxStore = openInMemoryBoxStore(dataDir)
        patternStore = PatternStore(boxStore, clock)
        cooldownStore = CalloutCooldownStore(boxStore)
        val detector = PatternDetector(boxStore, clock, ZoneOffset.UTC)
        val titleGenerator = PatternTitleGenerator(
            engine = engine,
            personaPromptComposer = { "P" },
            templateLoader = { "T" },
            forbiddenPhraseDetector = { false },
        )
        coEvery { engine.generateText(any(), any()) } returns "Aftermath Loop"
        orchestrator = PatternDetectionOrchestrator(
            boxStore = boxStore,
            detector = detector,
            patternStore = patternStore,
            titleGenerator = titleGenerator,
            cooldownStore = cooldownStore,
            clock = clock,
            zoneId = ZoneOffset.UTC,
            // Tests verify the cadence semantic; production threshold lives in the companion.
            patternSurfaceMinEntries = TEST_DETECTION_THRESHOLD,
        )
    }

    @After
    fun tearDown() {
        boxStore.closeAfterCleaningThreadResources()
        BoxStore.deleteAllFiles(dataDir)
    }

    private fun putEntry(
        templateLabel: TemplateLabel? = null,
        tags: List<String> = emptyList(),
        text: String = "",
        timestamp: Instant = now,
        extractionStatus: ExtractionStatus = ExtractionStatus.COMPLETED,
    ): EntryEntity {
        val entry = EntryEntity(
            entryText = text,
            templateLabel = templateLabel,
            timestampEpochMs = timestamp.toEpochMilli(),
            extractionStatus = extractionStatus,
        )
        val entryBox = boxStore.boxFor(EntryEntity::class.java)
        entryBox.put(entry)
        if (tags.isNotEmpty()) {
            val tagBox = boxStore.boxFor(TagEntity::class.java)
            val resolved = tags.map { name ->
                tagBox.all.firstOrNull { it.name == name } ?: TagEntity(name = name).also { tagBox.put(it) }
            }
            entry.tags.addAll(resolved)
            entryBox.put(entry)
        }
        return entry
    }

    /**
     * Seeds + commits one entry at a time, mirroring how the save flow drives the orchestrator.
     * Calls `settleReservedCallout(..., fired = true)` on any returned observation so the
     * cooldown advances exactly
     * as it would in production after `EntryStore.appendObservation` succeeds.
     */
    private suspend fun commitOne(
        templateLabel: TemplateLabel? = TemplateLabel.AFTERMATH,
        persona: Persona = Persona.WITNESS,
        target: PatternDetectionOrchestrator = orchestrator,
    ): EntryObservation? {
        val entry = putEntry(templateLabel = templateLabel)
        val callout = target.onEntryCommitted(entry, persona)
        if (callout != null) target.settleReservedCallout(entry, fired = true)
        return callout
    }

    /**
     * Builds an orchestrator that shares the suite's stores but ignores the detection threshold.
     * Used by tests whose contract is per-pattern callout selection on PRE-SEEDED patterns — there
     * the detector inserting a fresh shadow pattern at the entry-count threshold would surface a
     * second eligible candidate and mask the per-pattern cooldown the test is checking.
     */
    private fun orchestratorWithoutDetection(): PatternDetectionOrchestrator {
        val detector = PatternDetector(boxStore, clock, ZoneOffset.UTC)
        val titleGenerator = PatternTitleGenerator(
            engine = engine,
            personaPromptComposer = { "P" },
            templateLoader = { "T" },
            forbiddenPhraseDetector = { false },
        )
        return PatternDetectionOrchestrator(
            boxStore = boxStore,
            detector = detector,
            patternStore = patternStore,
            titleGenerator = titleGenerator,
            cooldownStore = cooldownStore,
            clock = clock,
            zoneId = ZoneOffset.UTC,
            patternSurfaceMinEntries = Long.MAX_VALUE,
        )
    }

    @Test
    fun `entries 1-2 do not trigger detection`() = runTest {
        repeat(2) { commitOne() }
        assertTrue("no detection until 3 entries committed", patternStore.all().isEmpty())
        commitOne() // 3rd — detection runs
        assertTrue(patternStore.all().any { it.kind == PatternKind.TEMPLATE_RECURRENCE })
    }

    @Test
    fun `completed entries between cadence boundaries do not rerun detection`() = runTest {
        repeat(3) { commitOne() }
        val patternId = patternStore.all().single { it.kind == PatternKind.TEMPLATE_RECURRENCE }.patternId
        assertEquals(3, patternStore.findByPatternId(patternId)!!.supportingEntries.size)

        commitOne()
        assertEquals(3, patternStore.findByPatternId(patternId)!!.supportingEntries.size)

        repeat(2) { commitOne() }
        assertEquals(6, patternStore.findByPatternId(patternId)!!.supportingEntries.size)
    }

    @Test
    fun `new pattern lands ACTIVE with a model-generated title`() = runTest {
        repeat(3) { commitOne() }
        val pattern = patternStore.all().first { it.kind == PatternKind.TEMPLATE_RECURRENCE }
        assertEquals(PatternState.ACTIVE, pattern.state)
        assertEquals("Aftermath Loop", pattern.title)
        assertTrue(pattern.latestCalloutText.isNotBlank())
        assertEquals(3, pattern.supportingEntries.size)
    }

    @Test
    fun `existing active pattern gets supportingEntries refreshed`() = runTest {
        repeat(3) { commitOne() }
        repeat(3) { commitOne() } // 6th entry → second detection run
        val pattern = patternStore.all().single { it.kind == PatternKind.TEMPLATE_RECURRENCE }
        assertEquals(6, pattern.supportingEntries.size)
    }

    @Test
    fun `dropped pattern's latestCalloutText is frozen on silent UPDATE branch`() = runTest {
        // Drive 3 entries → detector inserts an ACTIVE pattern with a generated callout.
        repeat(3) { commitOne() }
        val initial = patternStore.all().single { it.kind == PatternKind.TEMPLATE_RECURRENCE }
        val frozenText = initial.latestCalloutText
        assertTrue("seeded callout must be non-blank", frozenText.isNotBlank())

        // User drops the pattern.
        patternStore.transitionState(initial.patternId, PatternState.DROPPED)

        // Three more matching entries → another detection run upserts the same patternId.
        repeat(3) { commitOne() }
        val pattern = patternStore.findByPatternId(initial.patternId)!!
        assertEquals(PatternState.DROPPED, pattern.state)
        assertEquals(6, pattern.supportingEntries.size)
        assertEquals(
            "dropped pattern's callout text must not drift on silent update",
            frozenText,
            pattern.latestCalloutText,
        )
    }

    @Test
    fun `callout fires on matching active pattern and writes a PATTERN_CALLOUT observation`() = runTest {
        // Seed an active pattern manually so we can test the per-entry callout pathway in isolation.
        patternStore.put(
            PatternEntity(
                patternId = PATTERN_A_ID,
                kind = PatternKind.TEMPLATE_RECURRENCE,
                signatureJson = "{\"label\":\"aftermath\"}",
                title = "Aftermath",
                templateLabel = TemplateLabel.AFTERMATH.serial,
                firstSeenTimestamp = 1L,
                lastSeenTimestamp = 2L,
                state = PatternState.ACTIVE,
                latestCalloutText = "Worth noting.",
            ),
        )
        val entry = putEntry(templateLabel = TemplateLabel.AFTERMATH)
        val callout = orchestrator.onEntryCommitted(entry, Persona.WITNESS)
        assertNotNull(callout)
        assertEquals(ObservationEvidence.PATTERN_CALLOUT, callout!!.evidence)
        assertEquals("Worth noting.", callout.text)
    }

    @Test
    fun `cooldown suppresses callouts on the next three entries after firing`() = runTest {
        // Per-pattern semantic (ADR-016): A's window suppresses A specifically. Detection is
        // disabled so the detector can't insert an unrelated pattern at the entry-count threshold
        // and bypass A's window — that path is its own test ("per-pattern isolation").
        val target = orchestratorWithoutDetection()
        patternStore.put(
            PatternEntity(
                patternId = PATTERN_A_ID,
                kind = PatternKind.TEMPLATE_RECURRENCE,
                signatureJson = "{\"label\":\"aftermath\"}",
                title = "Aftermath",
                templateLabel = TemplateLabel.AFTERMATH.serial,
                firstSeenTimestamp = 1L,
                lastSeenTimestamp = 2L,
                state = PatternState.ACTIVE,
                latestCalloutText = "Worth noting.",
            ),
        )
        // Fire once.
        commitOne(target = target)
        // Next 3 entries: suppressed.
        repeat(3) {
            val callout = commitOne(target = target)
            assertNull("entry $it must be suppressed during the cooldown window", callout)
        }
        // Fourth eligible entry: callout fires again.
        val refired = commitOne(target = target)
        assertNotNull(refired)
    }

    @Test
    fun `non-matching entry does not fire even when active patterns exist`() = runTest {
        patternStore.put(
            PatternEntity(
                patternId = PATTERN_A_ID,
                kind = PatternKind.TEMPLATE_RECURRENCE,
                signatureJson = "{\"label\":\"aftermath\"}",
                title = "Aftermath",
                templateLabel = TemplateLabel.AFTERMATH.serial,
                firstSeenTimestamp = 1L,
                lastSeenTimestamp = 2L,
                state = PatternState.ACTIVE,
                latestCalloutText = "Worth noting.",
            ),
        )
        val unrelated = putEntry(templateLabel = TemplateLabel.TUNNEL_EXIT)
        assertNull(orchestrator.onEntryCommitted(unrelated, Persona.WITNESS))
    }

    @Test
    fun `non-matching entries still burn down the fired pattern's cooldown window`() = runTest {
        patternStore.put(
            PatternEntity(
                patternId = PATTERN_A_ID,
                kind = PatternKind.TEMPLATE_RECURRENCE,
                signatureJson = "{\"label\":\"aftermath\"}",
                title = "Aftermath",
                templateLabel = TemplateLabel.AFTERMATH.serial,
                firstSeenTimestamp = 1L,
                lastSeenTimestamp = 2L,
                state = PatternState.ACTIVE,
                latestCalloutText = "Worth noting.",
            ),
        )
        // Fire once → A's per-pattern cooldown counter set to 3.
        commitOne()
        // Three committed entries later — matching or not — A's counter has decremented to 0.
        repeat(3) { commitOne(templateLabel = TemplateLabel.TUNNEL_EXIT) }
        // Fourth entry after the callout is eligible again.
        val nextMatch = commitOne()
        assertNotNull("cooldown must burn across the next three committed entries", nextMatch)
    }

    @Test
    fun `onEntryCommitted holds a reservation until the save flow confirms or releases it`() = runTest {
        // The orchestrator reserves the matched pattern's per-pattern slot before returning the
        // callout. The save flow must confirm it after append succeeds or release it after append
        // fails — leaving a pending reservation wedges that pattern until clearStalePendingReservation.
        patternStore.put(
            PatternEntity(
                patternId = PATTERN_A_ID,
                kind = PatternKind.TEMPLATE_RECURRENCE,
                signatureJson = "{\"label\":\"aftermath\"}",
                title = "Aftermath",
                templateLabel = TemplateLabel.AFTERMATH.serial,
                firstSeenTimestamp = 1L,
                lastSeenTimestamp = 2L,
                state = PatternState.ACTIVE,
                latestCalloutText = "Worth noting.",
            ),
        )
        val entry = putEntry(templateLabel = TemplateLabel.AFTERMATH)
        val callout = orchestrator.onEntryCommitted(entry, Persona.WITNESS)
        assertNotNull(callout)
        assertEquals(
            "entry holds the pending reservation until append resolves",
            entry.id,
            cooldownStore.snapshotFor(PATTERN_A_ID).pendingCalloutEntryId,
        )
        assertEquals(
            "no fire confirmed yet, so suppression window stays at 0",
            0,
            cooldownStore.snapshotFor(PATTERN_A_ID).remainingSuppression,
        )
    }

    @Test
    fun `matched pattern with blank callout text returns null without touching cooldown`() = runTest {
        patternStore.put(
            PatternEntity(
                patternId = PATTERN_A_ID,
                kind = PatternKind.TEMPLATE_RECURRENCE,
                signatureJson = "{\"label\":\"aftermath\"}",
                title = "Aftermath",
                templateLabel = TemplateLabel.AFTERMATH.serial,
                firstSeenTimestamp = 1L,
                lastSeenTimestamp = 2L,
                state = PatternState.ACTIVE,
                latestCalloutText = "", // data-integrity smell — broken write path upstream
            ),
        )
        val first = orchestrator.onEntryCommitted(putEntry(templateLabel = TemplateLabel.AFTERMATH), Persona.WITNESS)
        assertNull(first)
        // Reservation was released, so a follow-up entry with valid pattern would fire normally.
        assertTrue(cooldownStore.isCalloutPermitted(PATTERN_A_ID))
    }

    @Test
    fun `pending reservation blocks another matching entry from sneaking through`() = runTest {
        patternStore.put(
            PatternEntity(
                patternId = PATTERN_A_ID,
                kind = PatternKind.TEMPLATE_RECURRENCE,
                signatureJson = "{\"label\":\"aftermath\"}",
                title = "Aftermath",
                templateLabel = TemplateLabel.AFTERMATH.serial,
                firstSeenTimestamp = 1L,
                lastSeenTimestamp = 2L,
                state = PatternState.ACTIVE,
                latestCalloutText = "Worth noting.",
            ),
        )
        val firstEntry = putEntry(templateLabel = TemplateLabel.AFTERMATH)
        val secondEntry = putEntry(templateLabel = TemplateLabel.AFTERMATH)

        val firstCallout = orchestrator.onEntryCommitted(firstEntry, Persona.WITNESS)
        val secondCallout = orchestrator.onEntryCommitted(secondEntry, Persona.WITNESS)

        assertNotNull(firstCallout)
        assertNull("second entry must block behind the in-flight reservation", secondCallout)
        assertEquals(firstEntry.id, cooldownStore.snapshotFor(PATTERN_A_ID).pendingCalloutEntryId)
    }

    @Test
    fun `dropped patterns do not surface as callouts even when matching`() = runTest {
        patternStore.put(
            PatternEntity(
                patternId = PATTERN_A_ID,
                kind = PatternKind.TEMPLATE_RECURRENCE,
                signatureJson = "{\"label\":\"aftermath\"}",
                title = "Aftermath",
                templateLabel = TemplateLabel.AFTERMATH.serial,
                firstSeenTimestamp = 1L,
                lastSeenTimestamp = 2L,
                state = PatternState.DROPPED,
                latestCalloutText = "Worth noting.",
            ),
        )
        val entry = putEntry(templateLabel = TemplateLabel.AFTERMATH)
        assertNull(orchestrator.onEntryCommitted(entry, Persona.WITNESS))
    }

    @Test
    fun `multiple matching active patterns — highest supporting count wins, then lastSeen`() = runTest {
        patternStore.put(
            PatternEntity(
                patternId = "p1".padEnd(64, 'a'),
                kind = PatternKind.TEMPLATE_RECURRENCE,
                signatureJson = "{\"label\":\"aftermath\"}",
                title = "Lower",
                templateLabel = TemplateLabel.AFTERMATH.serial,
                firstSeenTimestamp = 1L,
                lastSeenTimestamp = 100L,
                state = PatternState.ACTIVE,
                latestCalloutText = "Lower-support text.",
            ),
        )
        // Pattern 2 has more supporting entries → should win.
        val p2 = PatternEntity(
            patternId = "p2".padEnd(64, 'b'),
            kind = PatternKind.TEMPLATE_RECURRENCE,
            signatureJson = "{\"label\":\"aftermath\"}",
            title = "Higher",
            templateLabel = TemplateLabel.AFTERMATH.serial,
            firstSeenTimestamp = 1L,
            lastSeenTimestamp = 50L,
            state = PatternState.ACTIVE,
            latestCalloutText = "Higher-support text.",
        )
        patternStore.put(p2)
        val saved = patternStore.findByPatternId(p2.patternId)!!
        // Attach 5 dummy supporting entries.
        val dummies = (1..5).map {
            val e = EntryEntity(templateLabel = TemplateLabel.AFTERMATH, timestampEpochMs = now.toEpochMilli())
            boxStore.boxFor(EntryEntity::class.java).put(e)
            e
        }
        saved.supportingEntries.addAll(dummies)
        patternStore.put(saved)

        val callout = orchestrator.onEntryCommitted(putEntry(templateLabel = TemplateLabel.AFTERMATH), Persona.WITNESS)
        assertEquals("Higher-support text.", callout?.text)
    }

    @Test
    fun `equal supporting counts — lastSeenTimestamp tiebreak picks the most recent`() = runTest {
        // Two active patterns matching AFTERMATH, identical supporting counts (0 in this seed),
        // differ only on lastSeenTimestamp. The orchestrator must pick the more recent.
        patternStore.put(
            PatternEntity(
                patternId = "p1".padEnd(64, 'a'),
                kind = PatternKind.TEMPLATE_RECURRENCE,
                signatureJson = "{\"label\":\"aftermath\"}",
                title = "Older",
                templateLabel = TemplateLabel.AFTERMATH.serial,
                firstSeenTimestamp = 1L,
                lastSeenTimestamp = 100L,
                state = PatternState.ACTIVE,
                latestCalloutText = "older-text",
            ),
        )
        patternStore.put(
            PatternEntity(
                patternId = "p2".padEnd(64, 'b'),
                kind = PatternKind.TEMPLATE_RECURRENCE,
                signatureJson = "{\"label\":\"aftermath\"}",
                title = "Newer",
                templateLabel = TemplateLabel.AFTERMATH.serial,
                firstSeenTimestamp = 1L,
                lastSeenTimestamp = 200L,
                state = PatternState.ACTIVE,
                latestCalloutText = "newer-text",
            ),
        )
        val callout = orchestrator.onEntryCommitted(putEntry(templateLabel = TemplateLabel.AFTERMATH), Persona.WITNESS)
        assertEquals("newer-text", callout?.text)
    }

    @Test
    fun `snoozed pattern with expired snoozedUntil auto-promotes to ACTIVE on detection run`() = runTest {
        // Drive 3 entries → detector inserts ACTIVE pattern with model-generated title.
        repeat(3) { commitOne() }
        val original = patternStore.all().single { it.kind == PatternKind.TEMPLATE_RECURRENCE }

        // User snoozes 7 days.
        val snoozeUntil = now.toEpochMilli() + 7L * 24 * 60 * 60 * 1000
        patternStore.transitionState(original.patternId, PatternState.SNOOZED, snoozedUntilMs = snoozeUntil)
        assertEquals(PatternState.SNOOZED, patternStore.findByPatternId(original.patternId)!!.state)

        // Time advances past snoozedUntil; clock-bound store sees expiry. New orchestrator
        // with later clock — detector runs again on the next 3-entry tick.
        val laterClock = Clock.fixed(now.plusSeconds(8L * 24 * 60 * 60), ZoneOffset.UTC)
        val laterOrchestrator = PatternDetectionOrchestrator(
            boxStore = boxStore,
            detector = PatternDetector(boxStore, laterClock, ZoneOffset.UTC),
            patternStore = PatternStore(boxStore, laterClock),
            titleGenerator = PatternTitleGenerator(
                engine = engine,
                personaPromptComposer = { "P" },
                templateLoader = { "T" },
                forbiddenPhraseDetector = { false },
            ),
            cooldownStore = cooldownStore,
            clock = laterClock,
            zoneId = ZoneOffset.UTC,
            patternSurfaceMinEntries = TEST_DETECTION_THRESHOLD,
        )
        // Three more matching entries → detection upserts and promotes the row to ACTIVE.
        repeat(3) {
            laterOrchestrator.onEntryCommitted(putEntry(templateLabel = TemplateLabel.AFTERMATH), Persona.WITNESS)
        }
        val promoted = patternStore.findByPatternId(original.patternId)!!
        assertEquals(PatternState.ACTIVE, promoted.state)
        assertNull("snoozedUntil cleared on auto-promote", promoted.snoozedUntil)
    }

    @Test
    fun `snoozed pattern with unexpired snoozedUntil stays snoozed on detection run`() = runTest {
        repeat(3) { commitOne() }
        val original = patternStore.all().single { it.kind == PatternKind.TEMPLATE_RECURRENCE }

        val snoozeUntil = now.toEpochMilli() + 7L * 24 * 60 * 60 * 1000
        patternStore.transitionState(original.patternId, PatternState.SNOOZED, snoozedUntilMs = snoozeUntil)

        repeat(3) { commitOne() }
        val stillSnoozed = patternStore.findByPatternId(original.patternId)!!
        assertEquals(PatternState.SNOOZED, stillSnoozed.state)
        assertEquals(snoozeUntil, stillSnoozed.snoozedUntil)
    }

    @Test
    fun `new pattern inserts with deterministic title when generator returns null`() = runTest {
        // Title generator returns blank → orchestrator falls back to the deterministic title.
        coEvery { engine.generateText(any(), any()) } returns ""
        repeat(3) { commitOne() }
        val pattern = patternStore.all().first { it.kind == PatternKind.TEMPLATE_RECURRENCE }
        assertEquals(PatternState.ACTIVE, pattern.state)
        assertTrue("fallback title must be non-blank", pattern.title.isNotBlank())
        assertTrue(
            "fallback title is the kebab template label, title-cased",
            pattern.title.equals("Aftermath", ignoreCase = true),
        )
    }

    @Test
    fun `new pattern falls back to kind title and skips missing supporting rows when generator throws`() = runTest {
        val supporting = putEntry(templateLabel = TemplateLabel.AFTERMATH)
        repeat(1) { putEntry(templateLabel = TemplateLabel.AFTERMATH) }

        val detector: PatternDetector = mockk()
        coEvery { engine.generateText(any(), any()) } throws RuntimeException("boom")
        every { detector.detect() } returns listOf(
            DetectedPattern(
                patternId = "z".repeat(64),
                kind = PatternKind.COMMITMENT_RECURRENCE,
                signatureJson = "{\"kind\":\"commitment_recurrence\",\"topic_or_person\":\"jamie\"}",
                templateLabel = null,
                supportingEntryIds = listOf(supporting.id, 999_999L),
                firstSeenTimestamp = now.minusSeconds(60).toEpochMilli(),
                lastSeenTimestamp = now.toEpochMilli(),
            ),
        )
        val fallbackOrchestrator = PatternDetectionOrchestrator(
            boxStore = boxStore,
            detector = detector,
            patternStore = patternStore,
            titleGenerator = PatternTitleGenerator(
                engine = engine,
                personaPromptComposer = { "P" },
                templateLoader = { "T" },
                forbiddenPhraseDetector = { false },
            ),
            cooldownStore = cooldownStore,
            clock = clock,
            zoneId = ZoneOffset.UTC,
            patternSurfaceMinEntries = TEST_DETECTION_THRESHOLD,
        )

        fallbackOrchestrator.onEntryCommitted(putEntry(templateLabel = TemplateLabel.AFTERMATH), Persona.WITNESS)

        val pattern = patternStore.findByPatternId("z".repeat(64))!!
        assertEquals("Commitment recurrence", pattern.title)
        assertEquals(1, pattern.supportingEntries.size)
        assertEquals(supporting.id, pattern.supportingEntries.single().id)
    }

    @Test
    fun `new pattern insert rechecks inside the write transaction when another writer wins the race`() = runTest {
        val supporting = (1..3).map { putEntry(templateLabel = TemplateLabel.AFTERMATH) }
        val detector: PatternDetector = mockk()
        val detected = DetectedPattern(
            patternId = PATTERN_A_ID,
            kind = PatternKind.TEMPLATE_RECURRENCE,
            signatureJson = "{\"kind\":\"template_recurrence\",\"label\":\"aftermath\"}",
            templateLabel = TemplateLabel.AFTERMATH.serial,
            supportingEntryIds = supporting.map { it.id },
            firstSeenTimestamp = now.minusSeconds(60).toEpochMilli(),
            lastSeenTimestamp = now.toEpochMilli(),
        )
        every { detector.detect() } returns listOf(detected)
        coEvery { engine.generateText(any(), any()) } coAnswers {
            patternStore.put(
                PatternEntity(
                    patternId = PATTERN_A_ID,
                    kind = PatternKind.TEMPLATE_RECURRENCE,
                    signatureJson = detected.signatureJson,
                    title = "Concurrent Winner",
                    templateLabel = TemplateLabel.AFTERMATH.serial,
                    firstSeenTimestamp = 1L,
                    lastSeenTimestamp = 2L,
                    state = PatternState.ACTIVE,
                    latestCalloutText = "winner callout",
                ),
            )
            "Late Writer"
        }
        val raceOrchestrator = PatternDetectionOrchestrator(
            boxStore = boxStore,
            detector = detector,
            patternStore = patternStore,
            titleGenerator = PatternTitleGenerator(
                engine = engine,
                personaPromptComposer = { "P" },
                templateLoader = { "T" },
                forbiddenPhraseDetector = { false },
            ),
            cooldownStore = cooldownStore,
            clock = clock,
            zoneId = ZoneOffset.UTC,
            patternSurfaceMinEntries = 4L,
        )

        raceOrchestrator.onEntryCommitted(putEntry(templateLabel = TemplateLabel.AFTERMATH), Persona.WITNESS)

        val patterns = patternStore.all().filter { it.patternId == PATTERN_A_ID }
        assertEquals("unique patternId must stay a single row", 1, patterns.size)
        assertEquals("Concurrent Winner", patterns.single().title)
        assertEquals(supporting.map { it.id }.toSet(), patterns.single().supportingEntries.map { it.id }.toSet())
    }

    @Test
    fun `failed entries do not advance the every-3 completed-entry cadence`() = runTest {
        repeat(2) { commitOne() }
        repeat(2) {
            orchestrator.onEntryCommitted(
                putEntry(
                    templateLabel = TemplateLabel.AFTERMATH,
                    extractionStatus = ExtractionStatus.FAILED,
                ),
                Persona.WITNESS,
            )
        }

        assertTrue("failed entries must not trigger detection", patternStore.all().isEmpty())

        repeat(1) { commitOne() }
        assertTrue(patternStore.all().any { it.kind == PatternKind.TEMPLATE_RECURRENCE })
    }

    @Test
    fun `zero completed entries do not trigger detection`() = runTest {
        val detector: PatternDetector = mockk()
        every { detector.detect() } returns emptyList()
        val freshOrchestrator = PatternDetectionOrchestrator(
            boxStore = boxStore,
            detector = detector,
            patternStore = patternStore,
            titleGenerator = PatternTitleGenerator(
                engine = engine,
                personaPromptComposer = { "P" },
                templateLoader = { "T" },
                forbiddenPhraseDetector = { false },
            ),
            cooldownStore = cooldownStore,
            clock = clock,
            zoneId = ZoneOffset.UTC,
            patternSurfaceMinEntries = TEST_DETECTION_THRESHOLD,
        )

        freshOrchestrator.onEntryCommitted(
            putEntry(
                templateLabel = TemplateLabel.AFTERMATH,
                extractionStatus = ExtractionStatus.FAILED,
            ),
            Persona.WITNESS,
        )

        assertTrue(patternStore.all().isEmpty())
    }

    @Test(expected = CancellationException::class)
    fun `cancellation while generating a title is not swallowed`() = runTest {
        repeat(2) { putEntry(templateLabel = TemplateLabel.AFTERMATH) }
        val detector: PatternDetector = mockk()
        every { detector.detect() } returns listOf(
            DetectedPattern(
                patternId = "c".repeat(64),
                kind = PatternKind.TEMPLATE_RECURRENCE,
                signatureJson = "{\"kind\":\"template_recurrence\",\"label\":\"aftermath\"}",
                templateLabel = TemplateLabel.AFTERMATH.serial,
                supportingEntryIds = emptyList(),
                firstSeenTimestamp = now.minusSeconds(60).toEpochMilli(),
                lastSeenTimestamp = now.toEpochMilli(),
            ),
        )
        coEvery { engine.generateText(any(), any()) } throws CancellationException("stop")
        val cancelOrchestrator = PatternDetectionOrchestrator(
            boxStore = boxStore,
            detector = detector,
            patternStore = patternStore,
            titleGenerator = PatternTitleGenerator(
                engine = engine,
                personaPromptComposer = { "P" },
                templateLoader = { "T" },
                forbiddenPhraseDetector = { false },
            ),
            cooldownStore = cooldownStore,
            clock = clock,
            zoneId = ZoneOffset.UTC,
            patternSurfaceMinEntries = TEST_DETECTION_THRESHOLD,
        )

        cancelOrchestrator.onEntryCommitted(putEntry(templateLabel = TemplateLabel.AFTERMATH), Persona.WITNESS)
    }

    @Test
    fun `snoozed pattern without snoozedUntil does not auto-promote`() = runTest {
        repeat(3) { commitOne() }
        val original = patternStore.all().single { it.kind == PatternKind.TEMPLATE_RECURRENCE }
        val row = patternStore.findByPatternId(original.patternId)!!
        row.state = PatternState.SNOOZED
        row.snoozedUntil = null
        patternStore.put(row)

        repeat(3) { commitOne() }
        val persisted = patternStore.findByPatternId(original.patternId)!!
        assertEquals(PatternState.SNOOZED, persisted.state)
    }

    @Test
    fun `per-pattern isolation — A in cooldown but B fires the same entry`() = runTest {
        // Both patterns match the AFTERMATH template; A has higher supporting count → would win
        // the tiebreak if eligible. A is in suppression; B is clear. The selector must skip A.
        // Both patterns share the AFTERMATH template_label — PatternMatcher matches via the
        // signature's `label` field, so both rows must encode the same value. The patternIds
        // (and the per-pattern cooldown rows keyed off them) are what differentiates them here.
        patternStore.put(
            PatternEntity(
                patternId = PATTERN_A_ID,
                kind = PatternKind.TEMPLATE_RECURRENCE,
                signatureJson = "{\"label\":\"aftermath\"}",
                title = "Aftermath A",
                templateLabel = TemplateLabel.AFTERMATH.serial,
                firstSeenTimestamp = 1L,
                lastSeenTimestamp = 9L,
                state = PatternState.ACTIVE,
                latestCalloutText = "A says.",
            ),
        )
        patternStore.put(
            PatternEntity(
                patternId = PATTERN_B_ID,
                kind = PatternKind.TEMPLATE_RECURRENCE,
                signatureJson = "{\"label\":\"aftermath\"}",
                title = "Aftermath B",
                templateLabel = TemplateLabel.AFTERMATH.serial,
                firstSeenTimestamp = 1L,
                lastSeenTimestamp = 2L,
                state = PatternState.ACTIVE,
                latestCalloutText = "B says.",
            ),
        )
        // A's cooldown window is wide open; B is clear.
        cooldownStore.recordFired(entryId = 1L, patternId = PATTERN_A_ID, timestampMs = 1L)

        val callout = orchestrator.onEntryCommitted(putEntry(templateLabel = TemplateLabel.AFTERMATH), Persona.WITNESS)

        assertNotNull("unrelated B should fire while A is in cooldown", callout)
        assertEquals("B says.", callout?.text)
        // A's suppression window untouched by B's fire.
        assertEquals(3, cooldownStore.snapshotFor(PATTERN_A_ID).remainingSuppression)
    }

    @Test
    fun `settleReservedCallout fired keeps the fired pattern's window at full 3`() = runTest {
        // After commitOne fires A and settles, A.remainingSuppression must be 3 (not 2). Regression
        // catches: settle dropping `exceptPatternId` from decrementAllActive would burn the
        // freshly-set window to 2 on the same entry that set it.
        patternStore.put(
            PatternEntity(
                patternId = PATTERN_A_ID,
                kind = PatternKind.TEMPLATE_RECURRENCE,
                signatureJson = "{\"label\":\"aftermath\"}",
                title = "Aftermath",
                templateLabel = TemplateLabel.AFTERMATH.serial,
                firstSeenTimestamp = 1L,
                lastSeenTimestamp = 2L,
                state = PatternState.ACTIVE,
                latestCalloutText = "Worth noting.",
            ),
        )

        val callout = commitOne()

        assertNotNull(callout)
        assertEquals(3, cooldownStore.snapshotFor(PATTERN_A_ID).remainingSuppression)
    }

    @Test
    fun `selector picks the eligible weaker candidate when the stronger one is in cooldown`() = runTest {
        // A has 5 supporting entries (would win the tiebreak); B has none. A is in suppression.
        // Per ADR-016 + Phase 3, B should be the one to fire. Detection is disabled here because
        // the 5 inflate-entries would otherwise cross the threshold and let the detector mint a
        // shadow pattern — masking the per-pattern selector behavior under test.
        val target = orchestratorWithoutDetection()
        val aRow = PatternEntity(
            patternId = PATTERN_A_ID,
            kind = PatternKind.TEMPLATE_RECURRENCE,
            signatureJson = "{\"label\":\"aftermath\"}",
            title = "Aftermath A",
            templateLabel = TemplateLabel.AFTERMATH.serial,
            firstSeenTimestamp = 1L,
            lastSeenTimestamp = 9L,
            state = PatternState.ACTIVE,
            latestCalloutText = "A loud.",
        )
        patternStore.put(aRow)
        // Inflate A's supporting set so its tiebreak weight is real.
        val aSupporting = (1..5).map { putEntry(templateLabel = TemplateLabel.AFTERMATH) }
        val storedA = patternStore.findByPatternId(PATTERN_A_ID)!!
        storedA.supportingEntries.addAll(aSupporting)
        patternStore.put(storedA)
        patternStore.put(
            PatternEntity(
                patternId = PATTERN_B_ID,
                kind = PatternKind.TEMPLATE_RECURRENCE,
                signatureJson = "{\"label\":\"aftermath\"}",
                title = "Aftermath B",
                templateLabel = TemplateLabel.AFTERMATH.serial,
                firstSeenTimestamp = 1L,
                lastSeenTimestamp = 2L,
                state = PatternState.ACTIVE,
                latestCalloutText = "B quiet.",
            ),
        )
        cooldownStore.recordFired(entryId = 999L, patternId = PATTERN_A_ID, timestampMs = 1L)

        val callout = target.onEntryCommitted(putEntry(templateLabel = TemplateLabel.AFTERMATH), Persona.WITNESS)

        assertEquals("B quiet.", callout?.text)
    }

    @Test
    fun `vocab clustering second pass stamps vocabClustersJson on active VOCAB_FREQUENCY patterns`() = runTest {
        // Six entries reuse the same root word "tired"; their vectors split cleanly into two
        // axes so the agglomerative cut produces two clusters.
        val groupA = (1..3).map { i ->
            putEntry(text = "tired exhausted drained $i", timestamp = now.plusSeconds(i.toLong())).also {
                it.vector = nearAxisVector(axis = 0)
                boxStore.boxFor(EntryEntity::class.java).put(it)
            }
        }
        val groupB = (4..6).map { i ->
            putEntry(text = "tired sluggish foggy $i", timestamp = now.plusSeconds(i.toLong())).also {
                it.vector = nearAxisVector(axis = 1)
                boxStore.boxFor(EntryEntity::class.java).put(it)
            }
        }
        val supporting = groupA + groupB
        val pattern = PatternEntity(
            patternId = PATTERN_A_ID,
            kind = PatternKind.VOCAB_FREQUENCY,
            signatureJson = "{\"kind\":\"vocab_frequency\",\"token\":\"tired\"}",
            title = "Tired",
            templateLabel = null,
            firstSeenTimestamp = 1L,
            lastSeenTimestamp = 7L,
            state = PatternState.ACTIVE,
            latestCalloutText = "tired appears across 6 entries",
        )
        patternStore.put(pattern)
        val stored = patternStore.findByPatternId(PATTERN_A_ID)!!
        stored.supportingEntries.addAll(supporting)
        patternStore.put(stored)

        // A single commit at/above the detection threshold triggers the second pass.
        repeat(3) { commitOne() }

        val fresh = patternStore.findByPatternId(PATTERN_A_ID)!!
        val clusters = dev.anchildress1.vestige.storage.VocabClustersCodec.decode(fresh.vocabClustersJson)
        assertEquals("expected two clusters from two distinct vocabularies", 2, clusters.size)
        assertTrue(
            "clusters must cover every supporting entry",
            clusters.flatMap { it.memberEntryIds }.toSet() == supporting.map { it.id }.toSet(),
        )
    }

    @Test
    fun `vocab clustering second pass is a no-op when evidence is unchanged`() = runTest {
        val groupA = (1..3).map { i ->
            putEntry(text = "tired exhausted $i", timestamp = now.plusSeconds(i.toLong())).also {
                it.vector = nearAxisVector(axis = 0)
                boxStore.boxFor(EntryEntity::class.java).put(it)
            }
        }
        val groupB = (4..6).map { i ->
            putEntry(text = "tired foggy $i", timestamp = now.plusSeconds(i.toLong())).also {
                it.vector = nearAxisVector(axis = 1)
                boxStore.boxFor(EntryEntity::class.java).put(it)
            }
        }
        val pattern = PatternEntity(
            patternId = PATTERN_A_ID,
            kind = PatternKind.VOCAB_FREQUENCY,
            signatureJson = "{\"kind\":\"vocab_frequency\",\"token\":\"tired\"}",
            title = "Tired",
            templateLabel = null,
            firstSeenTimestamp = 1L,
            lastSeenTimestamp = 7L,
            state = PatternState.ACTIVE,
            latestCalloutText = "tired",
        )
        patternStore.put(pattern)
        val stored = patternStore.findByPatternId(PATTERN_A_ID)!!
        stored.supportingEntries.addAll(groupA + groupB)
        patternStore.put(stored)

        // First commit triggers the second pass.
        repeat(3) { commitOne() }
        val firstJson = patternStore.findByPatternId(PATTERN_A_ID)!!.vocabClustersJson
        assertTrue("first run must stamp", firstJson.isNotBlank())

        // Second commit: same evidence ⇒ same JSON ⇒ no re-stamp.
        commitOne()
        val secondJson = patternStore.findByPatternId(PATTERN_A_ID)!!.vocabClustersJson
        assertEquals("identical evidence must produce identical envelope", firstJson, secondJson)
    }

    @Test
    fun `vocab clustering does not stamp a pattern that is not ACTIVE`() = runTest {
        val members = (1..6).map { i ->
            putEntry(text = "tired $i", timestamp = now.plusSeconds(i.toLong())).also {
                it.vector = nearAxisVector(axis = 0)
                boxStore.boxFor(EntryEntity::class.java).put(it)
            }
        }
        val pattern = PatternEntity(
            patternId = PATTERN_A_ID,
            kind = PatternKind.VOCAB_FREQUENCY,
            signatureJson = "{\"kind\":\"vocab_frequency\",\"token\":\"tired\"}",
            title = "Tired",
            templateLabel = null,
            firstSeenTimestamp = 1L,
            lastSeenTimestamp = 7L,
            state = PatternState.SNOOZED, // not ACTIVE
            latestCalloutText = "tired",
        )
        patternStore.put(pattern)
        val stored = patternStore.findByPatternId(PATTERN_A_ID)!!
        stored.supportingEntries.addAll(members)
        patternStore.put(stored)

        repeat(3) { commitOne() }

        assertEquals("", patternStore.findByPatternId(PATTERN_A_ID)!!.vocabClustersJson)
    }

    @Test
    fun `vocab clustering ignores non-VOCAB_FREQUENCY active patterns`() = runTest {
        val members = (1..6).map { i ->
            putEntry(text = "tired $i", timestamp = now.plusSeconds(i.toLong())).also {
                it.vector = nearAxisVector(axis = 0)
                boxStore.boxFor(EntryEntity::class.java).put(it)
            }
        }
        val pattern = PatternEntity(
            patternId = PATTERN_A_ID,
            kind = PatternKind.TEMPORAL_RELATIVE,
            signatureJson = "{}",
            title = "T",
            templateLabel = null,
            firstSeenTimestamp = 1L,
            lastSeenTimestamp = 7L,
            state = PatternState.ACTIVE,
            latestCalloutText = "t",
        )
        patternStore.put(pattern)
        val stored = patternStore.findByPatternId(PATTERN_A_ID)!!
        stored.supportingEntries.addAll(members)
        patternStore.put(stored)

        repeat(3) { commitOne() }

        assertEquals("", patternStore.findByPatternId(PATTERN_A_ID)!!.vocabClustersJson)
    }

    @Test
    fun `vocab clustering is skipped when supporting set is below the minimum`() = runTest {
        val supporting = (1..3).map { i ->
            putEntry(text = "tired $i", timestamp = now.plusSeconds(i.toLong())).also {
                it.vector = nearAxisVector(axis = 0)
                boxStore.boxFor(EntryEntity::class.java).put(it)
            }
        }
        val pattern = PatternEntity(
            patternId = PATTERN_A_ID,
            kind = PatternKind.VOCAB_FREQUENCY,
            signatureJson = "{\"kind\":\"vocab_frequency\",\"token\":\"tired\"}",
            title = "Tired",
            templateLabel = null,
            firstSeenTimestamp = 1L,
            lastSeenTimestamp = 4L,
            state = PatternState.ACTIVE,
            latestCalloutText = "tired appears across 3 entries",
        )
        patternStore.put(pattern)
        val stored = patternStore.findByPatternId(PATTERN_A_ID)!!
        stored.supportingEntries.addAll(supporting)
        patternStore.put(stored)

        repeat(3) { commitOne() }

        val fresh = patternStore.findByPatternId(PATTERN_A_ID)!!
        assertEquals(
            "below the floor of ${dev.anchildress1.vestige.storage.EmbeddingClustering.MIN_SUPPORTING_ENTRIES}" +
                " the second pass is a no-op",
            "",
            fresh.vocabClustersJson,
        )
    }

    private fun nearAxisVector(axis: Int): FloatArray {
        val v = FloatArray(VOCAB_TEST_EMBED_DIM)
        v[axis % VOCAB_TEST_EMBED_DIM] = 1.0f
        return v
    }

    @Test
    fun `temporal pattern without analysisGenerator falls back to deterministic callout`() = runTest {
        // Three consecutive Tuesday afternoons → weekday_time_block TEMPORAL_RELATIVE
        val tuesdays = listOf(
            "2026-04-21T13:00:00Z",
            "2026-04-28T14:00:00Z",
            "2026-05-05T15:00:00Z",
        )
        tuesdays.forEach { ts ->
            orchestrator.onEntryCommitted(
                putEntry(templateLabel = null, text = "tuesday entry", timestamp = Instant.parse(ts)),
                Persona.WITNESS,
            )
        }

        val temporal = patternStore.all().single { it.kind == PatternKind.TEMPORAL_RELATIVE }
        // No analysisGenerator wired — callout must be the deterministic template, not blank.
        assertTrue("expected non-blank deterministic callout", temporal.latestCalloutText.isNotBlank())
        assertTrue(
            "expected temporal callout text, got: ${temporal.latestCalloutText}",
            temporal.latestCalloutText.contains("tuesday", ignoreCase = true) ||
                temporal.latestCalloutText.contains("entries logged"),
        )
    }

    private companion object {
        const val TEST_DETECTION_THRESHOLD: Long = 3
        const val PATTERN_A_ID: String = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val PATTERN_B_ID: String = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val VOCAB_TEST_EMBED_DIM: Int = 768 // Matches EntryEntity.EMBEDDING_DIMENSIONS
    }
}
