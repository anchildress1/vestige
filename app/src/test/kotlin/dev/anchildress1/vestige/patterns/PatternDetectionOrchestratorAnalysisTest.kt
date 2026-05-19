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
import dev.anchildress1.vestige.testing.newInMemoryObjectBoxDirectory
import dev.anchildress1.vestige.testing.openInMemoryBoxStore
import io.mockk.coEvery
import io.mockk.mockk
import io.objectbox.BoxStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
        )
    }

    @After
    fun tearDown() {
        boxStore.close()
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
}
