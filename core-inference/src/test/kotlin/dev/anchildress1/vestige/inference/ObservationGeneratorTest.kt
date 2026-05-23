package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.ConfidenceVerdict
import dev.anchildress1.vestige.model.EntryObservation
import dev.anchildress1.vestige.model.ObservationEvidence
import dev.anchildress1.vestige.model.ResolvedExtraction
import dev.anchildress1.vestige.model.ResolvedField
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class ObservationGeneratorTest {

    private val engine: LiteRtLmEngine = mockk()
    private val ioDispatcher = UnconfinedTestDispatcher()

    private fun newGenerator(parser: (String) -> List<EntryObservation>? = ObservationResponseParser::parse) =
        ObservationGenerator(
            engine = engine,
            parser = parser,
            systemPromptLoader = { "SYSTEM" },
            outputSchemaLoader = { "SCHEMA" },
            ioDispatcher = ioDispatcher,
        )

    @Test
    fun `generates observations from the model`() = runTest {
        val resolved = ResolvedExtraction(emptyMap())
        every { engine.streamText(any(), any()) } returns
            flowOf(themeNoticingPayload("You logged three boss mentions."))

        val observations = newGenerator().generate(SAMPLE_TEXT, resolved, SAMPLE_DAY)

        assertEquals(1, observations.size)
        assertEquals(ObservationEvidence.THEME_NOTICING, observations.first().evidence)
    }

    @Test
    fun `capture time is rendered into the prompt`() = runTest {
        val resolved = ResolvedExtraction(emptyMap())
        val capturedPrompt = io.mockk.slot<String>()
        every { engine.streamText(capture(capturedPrompt), any()) } returns
            flowOf(themeNoticingPayload("Theme noted."))

        newGenerator().generate(SAMPLE_TEXT, resolved, GOBLIN_HOUR)

        val prompt = capturedPrompt.captured
        assertTrue(prompt.contains("CAPTURE TIME"), "prompt should carry a capture-time section")
        assertTrue(prompt.contains("03:14"), "prompt should render the local capture clock time")
    }

    @Test
    fun `retries the model once when the first response contains a forbidden phrase`() = runTest {
        val resolved = ResolvedExtraction(emptyMap())
        every { engine.streamText(any(), any()) } returnsMany listOf(
            flowOf(
                """{"observations":[{"text":"You might feel stuck.","evidence":"theme-noticing","fields":[]}]}""",
            ),
            flowOf(
                """{"observations":[{"text":"Three boss mentions.","evidence":"theme-noticing","fields":["tags"]}]}""",
            ),
        )

        val observations = newGenerator().generate(SAMPLE_TEXT, resolved, SAMPLE_DAY)

        assertEquals(1, observations.size)
        assertEquals("Three boss mentions.", observations.first().text)
        verify(exactly = 2) { engine.streamText(any(), any()) }
    }

    @Test
    fun `returns empty list when both model attempts violate the voice rules`() = runTest {
        val resolved = ResolvedExtraction(emptyMap())
        every { engine.streamText(any(), any()) } returns flowOf(
            """{"observations":[{"text":"It seems you're stuck.","evidence":"theme-noticing","fields":[]}]}""",
        )

        val observations = newGenerator().generate(SAMPLE_TEXT, resolved, SAMPLE_DAY)

        assertTrue(observations.isEmpty())
        verify(exactly = 2) { engine.streamText(any(), any()) }
    }

    @Test
    fun `returns empty list when model throws on both attempts`() = runTest {
        val resolved = ResolvedExtraction(emptyMap())
        every { engine.streamText(any(), any()) } throws RuntimeException("native crash")

        val observations = newGenerator().generate(SAMPLE_TEXT, resolved, SAMPLE_DAY)

        assertTrue(observations.isEmpty())
        verify(exactly = 2) { engine.streamText(any(), any()) }
    }

    @Test
    fun `blank entryText is rejected before any model call`() = runTest {
        val resolved = ResolvedExtraction(emptyMap())
        try {
            newGenerator().generate("   ", resolved, SAMPLE_DAY)
            org.junit.jupiter.api.Assertions.fail<Unit>("expected blank entryText to throw")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("non-blank"))
        }
        verify(exactly = 0) { engine.streamText(any(), any()) }
    }

    @Test
    fun `prompt renders all resolved field value shapes`() = runTest {
        val resolved = ResolvedExtraction(
            mapOf(
                "tags" to ResolvedField(listOf("focus", "long-stretch"), ConfidenceVerdict.CANONICAL),
                "template_label" to ResolvedField("locked-in", ConfidenceVerdict.CANONICAL_WITH_CONFLICT),
                "recurrence_link" to ResolvedField(null, ConfidenceVerdict.AMBIGUOUS),
                "some_flag" to ResolvedField(true, ConfidenceVerdict.CANONICAL),
                "nested" to ResolvedField(mapOf("a" to 1, "b" to listOf(2, 3)), ConfidenceVerdict.CANDIDATE),
            ),
        )
        val capturedPrompt = io.mockk.slot<String>()
        every { engine.streamText(capture(capturedPrompt), any()) } returns
            flowOf(themeNoticingPayload("Theme noted."))

        newGenerator().generate(SAMPLE_TEXT, resolved, SAMPLE_DAY)

        val prompt = capturedPrompt.captured
        // String / List / Map / Boolean / null branches in renderValue all exercised.
        assertTrue(prompt.contains("[\"focus\""), "prompt should render tags list")
        assertTrue(prompt.contains("\"locked-in\""), "prompt should render scalar string")
        assertTrue(prompt.contains("recurrence_link"), "prompt should render null")
        assertTrue(prompt.contains("some_flag"), "prompt should render boolean")
        assertTrue(prompt.contains("a=1"), "prompt should render nested map")
    }

    @Test
    fun `prompt renders the no-fields sentinel when resolved is empty`() = runTest {
        val resolved = ResolvedExtraction(emptyMap())
        val capturedPrompt = io.mockk.slot<String>()
        every { engine.streamText(capture(capturedPrompt), any()) } returns
            flowOf(themeNoticingPayload("Empty entry observation."))

        newGenerator().generate(SAMPLE_TEXT, resolved, SAMPLE_DAY)

        assertTrue(capturedPrompt.captured.contains("(no resolved fields)"))
    }

    private fun themeNoticingPayload(text: String): String =
        "{\"observations\":[{\"text\":\"$text\",\"evidence\":\"theme-noticing\",\"fields\":[\"tags\"]}]}"

    private companion object {
        // 2026-05-11 14:00 America/New_York — an ordinary daytime capture.
        private val SAMPLE_DAY: ZonedDateTime =
            ZonedDateTime.of(2026, 5, 11, 14, 0, 0, 0, ZoneId.of("America/New_York"))

        // 2026-05-11 03:14 America/New_York — an odd-hour capture the model may choose to note.
        private val GOBLIN_HOUR: ZonedDateTime =
            ZonedDateTime.of(2026, 5, 11, 3, 14, 0, 0, ZoneId.of("America/New_York"))
        private const val SAMPLE_TEXT = "Standup ran long again. The doc is still not sent."
    }
}
