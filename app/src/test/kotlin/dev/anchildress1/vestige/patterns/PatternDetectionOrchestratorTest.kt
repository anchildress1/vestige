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
        )
    }

    @After
    fun tearDown() {
        boxStore.close()
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
    ): EntryObservation? {
        val entry = putEntry(templateLabel = templateLabel)
        val callout = orchestrator.onEntryCommitted(entry, persona)
        if (callout != null) orchestrator.settleReservedCallout(entry, fired = true)
        return callout
    }

    @Test
    fun `entries 1-2 do not trigger detection`() = runTest {
        repeat(2) { commitOne() }
        assertTrue("no detection until 3 entries committed", patternStore.all().isEmpty())
        commitOne() // 3rd — detection runs
        assertTrue(patternStore.all().any { it.kind == PatternKind.TEMPLATE_RECURRENCE })
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
                patternId = "x".repeat(64),
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
        patternStore.put(
            PatternEntity(
                patternId = "x".repeat(64),
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
        commitOne()
        // Next 3 entries: suppressed.
        repeat(3) {
            val callout = commitOne()
            assertNull("entry $it must be suppressed during the cooldown window", callout)
        }
        // Fourth eligible entry: callout fires again.
        val refired = commitOne()
        assertNotNull(refired)
    }

    @Test
    fun `non-matching entry does not fire even when active patterns exist`() = runTest {
        patternStore.put(
            PatternEntity(
                patternId = "x".repeat(64),
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
    fun `non-matching entries still consume the global cooldown window`() = runTest {
        patternStore.put(
            PatternEntity(
                patternId = "x".repeat(64),
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
        // Fire once → cooldown counter set to 3.
        commitOne()
        // Three committed entries later — matching or not — the global cooldown is spent.
        repeat(3) { commitOne(templateLabel = TemplateLabel.TUNNEL_EXIT) }
        // Fourth entry after the callout is eligible again.
        val nextMatch = commitOne()
        assertNotNull("cooldown must burn across the next three committed entries", nextMatch)
    }

    @Test
    fun `onEntryCommitted holds a reservation until the save flow confirms or releases it`() = runTest {
        // The orchestrator now reserves the single global slot before returning the callout.
        // The save flow must confirm it after append succeeds or release it after append fails.
        patternStore.put(
            PatternEntity(
                patternId = "x".repeat(64),
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
            cooldownStore.snapshot().pendingCalloutEntryId,
        )
        assertEquals(
            "no fire confirmed yet, so suppression window stays at 0",
            0,
            cooldownStore.snapshot().remainingSuppression,
        )
    }

    @Test
    fun `matched pattern with blank callout text returns null without touching cooldown`() = runTest {
        patternStore.put(
            PatternEntity(
                patternId = "x".repeat(64),
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
        assertTrue(cooldownStore.isCalloutPermitted())
    }

    @Test
    fun `pending reservation blocks another matching entry from sneaking through`() = runTest {
        patternStore.put(
            PatternEntity(
                patternId = "x".repeat(64),
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
        assertEquals(firstEntry.id, cooldownStore.snapshot().pendingCalloutEntryId)
    }

    @Test
    fun `dropped patterns do not surface as callouts even when matching`() = runTest {
        patternStore.put(
            PatternEntity(
                patternId = "x".repeat(64),
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
        )

        fallbackOrchestrator.onEntryCommitted(putEntry(templateLabel = TemplateLabel.AFTERMATH), Persona.WITNESS)

        val pattern = patternStore.findByPatternId("z".repeat(64))!!
        assertEquals("Commitment recurrence", pattern.title)
        assertEquals(1, pattern.supportingEntries.size)
        assertEquals(supporting.id, pattern.supportingEntries.single().id)
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
}
