package dev.anchildress1.vestige.patterns

import dev.anchildress1.vestige.inference.LiteRtLmEngine
import dev.anchildress1.vestige.inference.PatternAnalysisGenerator
import dev.anchildress1.vestige.inference.PatternTitleGenerator
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.PatternState
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.model.TemplateLabel
import dev.anchildress1.vestige.storage.CalloutCooldownStore
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.PatternDetector
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.PatternStore
import dev.anchildress1.vestige.storage.closeAfterCleaningThreadResources
import dev.anchildress1.vestige.testing.newInMemoryObjectBoxDirectory
import dev.anchildress1.vestige.testing.openInMemoryBoxStore
import io.mockk.coEvery
import io.mockk.mockk
import io.objectbox.BoxStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
class PatternDetectionOrchestratorAnalysisTest {

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
        dataDir = newInMemoryObjectBoxDirectory("objectbox-orch-analysis-")
        boxStore = openInMemoryBoxStore(dataDir)
        patternStore = PatternStore(boxStore, clock)
        cooldownStore = CalloutCooldownStore(boxStore)
        orchestrator = PatternDetectionOrchestrator(
            boxStore = boxStore,
            detector = PatternDetector(boxStore, clock, ZoneOffset.UTC),
            patternStore = patternStore,
            titleGenerator = PatternTitleGenerator(
                engine = engine,
                personaPromptComposer = { "P" },
                templateLoader = { "T" },
                forbiddenPhraseDetector = { false },
            ),
            analysisGenerator = PatternAnalysisGenerator(
                engine = engine,
                personaPromptComposer = { "P" },
                templateLoader = { "T" },
                forbiddenPhraseDetector = { false },
                zoneId = ZoneOffset.UTC,
            ),
            cooldownStore = cooldownStore,
            clock = clock,
            zoneId = ZoneOffset.UTC,
            // Production threshold (companion default) is 10. These tests assert temporal-analysis
            // wiring on a handful of seeded entries; pin a small threshold so detection actually
            // runs at the cadence the test authors designed around.
            patternDetectionCadence = 3L,
        )
    }

    @After
    fun tearDown() {
        boxStore.closeAfterCleaningThreadResources()
        BoxStore.deleteAllFiles(dataDir)
    }

    @Test
    fun `temporal pattern persists model analysis title and callout`() = runTest {
        coEvery { engine.generateText(any(), any()) } returns
            "{\"title\":\"Tuesday Drag\",\"callout\":\"You keep landing here on Tuesday afternoons.\"}"
        listOf(
            "2026-04-21T13:00:00Z",
            "2026-04-28T14:00:00Z",
            "2026-05-05T15:00:00Z",
        ).forEach { timestamp ->
            orchestrator.onEntryCommitted(
                putEntry(
                    templateLabel = null,
                    text = "forgot to capture the follow-up",
                    timestamp = Instant.parse(timestamp),
                ),
                Persona.HARDASS,
            )
        }

        val temporal = patternStore.all().single { it.kind == PatternKind.TEMPORAL_RELATIVE }
        assertEquals("Tuesday Drag", temporal.title)
        assertEquals("You keep landing here on Tuesday afternoons.", temporal.latestCalloutText)
    }

    @Test
    fun `temporal pattern takes priority over deterministic template match`() = runTest {
        patternStore.put(
            PatternEntity(
                patternId = "p1".padEnd(64, 'a'),
                kind = PatternKind.TEMPLATE_RECURRENCE,
                signatureJson = "{\"label\":\"aftermath\"}",
                title = "Template",
                templateLabel = TemplateLabel.AFTERMATH.serial,
                firstSeenTimestamp = 1L,
                lastSeenTimestamp = 500L,
                state = PatternState.ACTIVE,
                latestCalloutText = "Template text.",
            ),
        )
        val template = patternStore.findByPatternId("p1".padEnd(64, 'a'))!!
        val support = (1..5).map {
            EntryEntity(templateLabel = TemplateLabel.AFTERMATH, timestampEpochMs = now.toEpochMilli()).also {
                boxStore.boxFor(EntryEntity::class.java).put(it)
            }
        }
        template.supportingEntries.addAll(support)
        patternStore.put(template)
        patternStore.put(
            PatternEntity(
                patternId = "p2".padEnd(64, 'b'),
                kind = PatternKind.TEMPORAL_RELATIVE,
                signatureJson = "{\"kind\":\"temporal_relative\",\"relation\":\"weekday_time_block\"," +
                    "\"day_of_week\":\"monday\",\"time_block\":\"afternoon\"}",
                title = "Monday Loop",
                firstSeenTimestamp = 1L,
                lastSeenTimestamp = 100L,
                state = PatternState.ACTIVE,
                latestCalloutText = "Temporal text.",
            ),
        )

        val callout = orchestrator.onEntryCommitted(putEntry(templateLabel = TemplateLabel.AFTERMATH), Persona.WITNESS)

        assertEquals("Temporal text.", callout?.text)
    }

    @Test
    fun `existing active temporal pattern updates title and callout from later analysis`() = runTest {
        coEvery { engine.generateText(any(), any()) } returnsMany listOf(
            "{\"title\":\"First Cut\",\"callout\":\"You started landing here on Tuesday afternoons.\"}",
            "{\"title\":\"Tuesday Drag\",\"callout\":\"You keep landing here on Tuesday afternoons.\"}",
        )

        commitTuesdayAfternoons(SIX_DISTINCT_TEXTS)

        val temporal = patternStore.all().single { it.kind == PatternKind.TEMPORAL_RELATIVE }
        assertEquals("Tuesday Drag", temporal.title)
        assertEquals("You keep landing here on Tuesday afternoons.", temporal.latestCalloutText)
    }

    @Test
    fun `snoozed temporal pattern freezes the callout the user last saw`() = runTest {
        coEvery { engine.generateText(any(), any()) } returnsMany listOf(
            "{\"title\":\"First Cut\",\"callout\":\"You started landing here on Tuesday afternoons.\"}",
            "{\"title\":\"Drifted\",\"callout\":\"This must not overwrite the frozen callout.\"}",
        )

        commitTuesdayAfternoons(SIX_DISTINCT_TEXTS.take(3))
        val patternId = patternStore.all().single { it.kind == PatternKind.TEMPORAL_RELATIVE }.patternId
        patternStore.transitionState(
            patternId,
            PatternState.SNOOZED,
            snoozedUntilMs = now.toEpochMilli() + 30L * 24 * 60 * 60 * 1000,
        )

        commitTuesdayAfternoons(SIX_DISTINCT_TEXTS.drop(3), startIndex = 3)

        val temporal = patternStore.findByPatternId(patternId)!!
        assertEquals(PatternState.SNOOZED, temporal.state)
        assertEquals("First Cut", temporal.title)
        assertEquals("You started landing here on Tuesday afternoons.", temporal.latestCalloutText)
    }

    @Test
    fun `generator failure persists a deterministic fallback, not an empty callout`() = runTest {
        coEvery { engine.generateText(any(), any()) } throws RuntimeException("oom")

        commitTuesdayAfternoons(SIX_DISTINCT_TEXTS.take(3))

        val temporal = patternStore.all().single { it.kind == PatternKind.TEMPORAL_RELATIVE }
        assertTrue("title must not be blank", temporal.title.isNotBlank())
        assertTrue("callout must not be blank", temporal.latestCalloutText.isNotBlank())
        assertTrue("callout must not be model JSON", !temporal.latestCalloutText.contains("{"))
    }

    @Test
    fun `cancellation during analysis propagates and is not swallowed`() = runTest {
        coEvery { engine.generateText(any(), any()) } throws CancellationException("stop")

        val thrown = runCatching {
            commitTuesdayAfternoons(SIX_DISTINCT_TEXTS.take(3))
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertNotNull(thrown)
    }

    private suspend fun commitTuesdayAfternoons(texts: List<String>, startIndex: Int = 0) {
        texts.forEachIndexed { offset, text ->
            val tuesday = TUESDAYS[startIndex + offset]
            orchestrator.onEntryCommitted(
                putEntry(templateLabel = null, text = text, timestamp = Instant.parse(tuesday)),
                Persona.WITNESS,
            )
        }
    }

    private fun putEntry(templateLabel: TemplateLabel?, text: String = "", timestamp: Instant = now): EntryEntity {
        val entry = EntryEntity(
            entryText = text,
            templateLabel = templateLabel,
            timestampEpochMs = timestamp.toEpochMilli(),
            extractionStatus = ExtractionStatus.COMPLETED,
        )
        boxStore.boxFor(EntryEntity::class.java).put(entry)
        return entry
    }

    private companion object {
        // Six consecutive Tuesdays at 14:00Z (afternoon block) — same weekday_time_block
        // signature across runs. Distinct entry text per row so no vocab pattern co-forms,
        // keeping exactly one analysis engine call per detection cycle.
        val TUESDAYS = listOf(
            "2026-03-31T14:00:00Z",
            "2026-04-07T14:00:00Z",
            "2026-04-14T14:00:00Z",
            "2026-04-21T14:00:00Z",
            "2026-04-28T14:00:00Z",
            "2026-05-05T14:00:00Z",
        )
        val SIX_DISTINCT_TEXTS = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot")
    }
}
