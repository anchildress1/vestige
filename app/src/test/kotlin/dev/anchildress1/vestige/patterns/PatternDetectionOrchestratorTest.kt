package dev.anchildress1.vestige.patterns

import androidx.test.core.app.ApplicationProvider
import dev.anchildress1.vestige.inference.LiteRtLmEngine
import dev.anchildress1.vestige.inference.PatternTitleGenerator
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
import dev.anchildress1.vestige.storage.VestigeBoxStore
import io.mockk.coEvery
import io.mockk.mockk
import io.objectbox.BoxStore
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
@Config(manifest = Config.NONE)
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
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        dataDir = File(context.filesDir, "objectbox-orch-${System.nanoTime()}")
        boxStore = VestigeBoxStore.openAt(dataDir)
        patternStore = PatternStore(boxStore, clock)
        cooldownStore = CalloutCooldownStore(boxStore)
        val detector = PatternDetector(boxStore, clock, ZoneOffset.UTC)
        val titleGenerator = PatternTitleGenerator(
            engine = engine,
            personaPromptComposer = { "P" },
            templateLoader = { "T" },
            forbiddenPhraseDetector = { false },
        )
        coEvery { engine.generateText(any()) } returns "Aftermath Loop"
        orchestrator = PatternDetectionOrchestrator(
            boxStore = boxStore,
            detector = detector,
            patternStore = patternStore,
            titleGenerator = titleGenerator,
            cooldownStore = cooldownStore,
            activePersonaProvider = { Persona.WITNESS },
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
    ): EntryEntity {
        val entry = EntryEntity(
            entryText = text,
            templateLabel = templateLabel,
            timestampEpochMs = timestamp.toEpochMilli(),
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

    /** Seeds + commits one entry at a time, mirroring how the save flow drives the orchestrator. */
    private suspend fun commitOne(templateLabel: TemplateLabel? = TemplateLabel.AFTERMATH) =
        orchestrator.onEntryCommitted(putEntry(templateLabel = templateLabel))

    @Test
    fun `entries 1-9 do not trigger detection`() = runTest {
        repeat(9) { commitOne() }
        assertTrue("no detection until 10 entries committed", patternStore.all().isEmpty())
        commitOne() // 10th — detection runs
        assertTrue(patternStore.all().any { it.kind == PatternKind.TEMPLATE_RECURRENCE })
    }

    @Test
    fun `new pattern lands ACTIVE with a model-generated title`() = runTest {
        repeat(10) { commitOne() }
        val pattern = patternStore.all().first { it.kind == PatternKind.TEMPLATE_RECURRENCE }
        assertEquals(PatternState.ACTIVE, pattern.state)
        assertEquals("Aftermath Loop", pattern.title)
        assertTrue(pattern.latestCalloutText.isNotBlank())
        assertEquals(10, pattern.supportingEntries.size)
    }

    @Test
    fun `existing active pattern gets supportingEntries refreshed`() = runTest {
        repeat(10) { commitOne() }
        repeat(10) { commitOne() } // 20th entry → second detection run
        val pattern = patternStore.all().single { it.kind == PatternKind.TEMPLATE_RECURRENCE }
        assertEquals(20, pattern.supportingEntries.size)
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
        val callout = orchestrator.onEntryCommitted(entry)
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
        orchestrator.onEntryCommitted(putEntry(templateLabel = TemplateLabel.AFTERMATH))
        // Next 3 entries: suppressed.
        repeat(3) {
            val callout = orchestrator.onEntryCommitted(putEntry(templateLabel = TemplateLabel.AFTERMATH))
            assertNull("entry $it must be suppressed during the cooldown window", callout)
        }
        // Fourth eligible entry: callout fires again.
        val refired = orchestrator.onEntryCommitted(putEntry(templateLabel = TemplateLabel.AFTERMATH))
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
        assertNull(orchestrator.onEntryCommitted(unrelated))
    }

    @Test
    fun `non-matching entries leave the cooldown counter alone`() = runTest {
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
        orchestrator.onEntryCommitted(putEntry(templateLabel = TemplateLabel.AFTERMATH))
        // Three non-matching entries in a row must NOT decrement the counter — only
        // suppressed candidates count toward the window.
        repeat(3) {
            orchestrator.onEntryCommitted(putEntry(templateLabel = TemplateLabel.TUNNEL_EXIT))
        }
        // Counter is still 3 → the next matching entry is still suppressed.
        val nextMatch = orchestrator.onEntryCommitted(putEntry(templateLabel = TemplateLabel.AFTERMATH))
        assertNull("cooldown must still be active because non-match entries did not consume slots", nextMatch)
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
        val first = orchestrator.onEntryCommitted(putEntry(templateLabel = TemplateLabel.AFTERMATH))
        assertNull(first)
        // Cooldown was never started, so a follow-up entry with valid pattern would fire normally.
        assertTrue(cooldownStore.isCalloutPermitted())
    }

    @Test
    fun `dismissed patterns do not surface as callouts even when matching`() = runTest {
        patternStore.put(
            PatternEntity(
                patternId = "x".repeat(64),
                kind = PatternKind.TEMPLATE_RECURRENCE,
                signatureJson = "{\"label\":\"aftermath\"}",
                title = "Aftermath",
                templateLabel = TemplateLabel.AFTERMATH.serial,
                firstSeenTimestamp = 1L,
                lastSeenTimestamp = 2L,
                state = PatternState.DISMISSED,
                latestCalloutText = "Worth noting.",
            ),
        )
        val entry = putEntry(templateLabel = TemplateLabel.AFTERMATH)
        assertNull(orchestrator.onEntryCommitted(entry))
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

        val callout = orchestrator.onEntryCommitted(putEntry(templateLabel = TemplateLabel.AFTERMATH))
        assertEquals("Higher-support text.", callout?.text)
    }
}
