package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.DetectedPattern
import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.Persona
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class PatternAnalysisGeneratorTest {

    private val engine: LiteRtLmEngine = mockk()
    private val dispatcher = UnconfinedTestDispatcher()

    private fun newGenerator(forbidden: (String) -> Boolean = { false }) = PatternAnalysisGenerator(
        engine = engine,
        personaPromptComposer = { "PERSONA" },
        templateLoader = { "TEMPLATE" },
        forbiddenPhraseDetector = forbidden,
        zoneId = ZoneOffset.UTC,
        ioDispatcher = dispatcher,
    )

    private fun temporalPattern() = DetectedPattern(
        patternId = "deadbeef".repeat(8),
        kind = PatternKind.TEMPORAL_RELATIVE,
        signatureJson = "{\"kind\":\"temporal_relative\",\"relation\":\"weekday_time_block\"," +
            "\"day_of_week\":\"tuesday\",\"time_block\":\"afternoon\"}",
        templateLabel = null,
        supportingEntryIds = listOf(1L, 2L, 3L),
        firstSeenTimestamp = 1_000L,
        lastSeenTimestamp = 3_000L,
    )

    private fun templatePattern() = temporalPattern().copy(kind = PatternKind.TEMPLATE_RECURRENCE)

    private fun evidence() = listOf(
        PatternEvidenceEntry(
            id = 1L,
            timestampEpochMs = 1_779_197_400_000L,
            text = "forgot the thing after the meeting",
            tags = listOf("meeting"),
            templateLabel = null,
        ),
    )

    @Test
    fun `returns parsed JSON title and callout`() = runTest {
        coEvery { engine.generateText(any(), any()) } returns
            "{\"title\":\"Tuesday Drag\",\"callout\":\"You keep landing here on Tuesday afternoons.\"}"

        val out = newGenerator().generate(Persona.HARDASS, temporalPattern(), evidence())

        assertEquals(PatternAnalysisResult("Tuesday Drag", "You keep landing here on Tuesday afternoons."), out)
    }

    @Test
    fun `locates JSON inside surrounding prose or fence`() = runTest {
        coEvery { engine.generateText(any(), any()) } returns
            "```json\n{\"title\":\"Month Start\",\"callout\":\"You keep circling the first of the month.\"}\n```"

        val out = newGenerator().generate(Persona.WITNESS, temporalPattern(), evidence())

        assertEquals("Month Start", out?.title)
        assertEquals("You keep circling the first of the month.", out?.calloutText)
    }

    @Test
    fun `rejects blank fields`() = runTest {
        coEvery { engine.generateText(any(), any()) } returns
            "{\"title\":\"\",\"callout\":\"You keep landing here.\"}"

        assertNull(
            newGenerator(forbidden = ObservationResponseParser::containsForbiddenPhrase)
                .generate(Persona.WITNESS, temporalPattern(), evidence()),
        )
    }

    @Test
    fun `rejects overlong callout`() = runTest {
        coEvery { engine.generateText(any(), any()) } returns
            "{\"title\":\"Tuesday Drag\",\"callout\":\"${"x".repeat(PatternAnalysisGenerator.MAX_CALLOUT_CHARS + 1)}\"}"

        assertNull(
            newGenerator(forbidden = ObservationResponseParser::containsForbiddenPhrase)
                .generate(Persona.WITNESS, temporalPattern(), evidence()),
        )
    }

    @Test
    fun `rejects forbidden language`() = runTest {
        coEvery { engine.generateText(any(), any()) } returns
            "{\"title\":\"Tuesday Drag\",\"callout\":\"You should write it down next time.\"}"

        assertNull(
            newGenerator(forbidden = ObservationResponseParser::containsForbiddenPhrase)
                .generate(Persona.WITNESS, temporalPattern(), evidence()),
        )
    }

    @Test
    fun `does not call model for non-temporal patterns`() = runTest {
        val out = newGenerator().generate(Persona.WITNESS, templatePattern(), evidence())

        assertNull(out)
        coVerify(exactly = 0) { engine.generateText(any(), any()) }
    }

    @Test
    fun `passes timestamped evidence to model prompt`() = runTest {
        coEvery { engine.generateText(any(), any()) } returns
            "{\"title\":\"Tuesday Drag\",\"callout\":\"You keep landing here on Tuesday afternoons.\"}"

        newGenerator().generate(Persona.WITNESS, temporalPattern(), evidence())

        coVerify {
            engine.generateText(
                match { it.contains("PERSONA") && it.contains("TEMPLATE") },
                match { it.contains("2026-05-19T13:30:00Z") && it.contains("forgot the thing") },
            )
        }
    }

    @Test
    fun `swallows engine exceptions and returns null`() = runTest {
        coEvery { engine.generateText(any(), any()) } throws RuntimeException("oom")

        assertNull(newGenerator().generate(Persona.WITNESS, temporalPattern(), evidence()))
    }

    @Test
    fun `cancellation is not swallowed`() = runTest {
        coEvery { engine.generateText(any(), any()) } throws CancellationException("stop")

        val thrown = runCatching {
            newGenerator().generate(Persona.WITNESS, temporalPattern(), evidence())
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
    }
}
