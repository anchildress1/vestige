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
    fun `commitment in resolved fields short-circuits to deterministic commitment-flag observation`() = runTest {
        val resolved = ResolvedExtraction(
            mapOf(
                "stated_commitment" to ResolvedField(
                    mapOf("text" to "talk to Nora before Friday", "topic_or_person" to "Nora"),
                    ConfidenceVerdict.CANONICAL,
                ),
            ),
        )

        val observations = newGenerator().generate(SAMPLE_TEXT, resolved, SAMPLE_DAY)

        assertEquals(1, observations.size)
        val obs = observations.first()
        assertEquals(ObservationEvidence.COMMITMENT_FLAG, obs.evidence)
        assertTrue(obs.text.contains("talk to Nora before Friday"))
        assertTrue(obs.text.contains("Nora"))
        assertEquals(listOf("stated_commitment"), obs.fields)
        verify(exactly = 0) { engine.streamText(any(), any()) }
    }

    @Test
    fun `goblin hours capture produces a volunteered-context observation when no other signal exists`() = runTest {
        val resolved = ResolvedExtraction(emptyMap())
        val capturedAt = ZonedDateTime.of(2026, 5, 11, 3, 14, 0, 0, ZoneId.of("America/New_York"))

        val observations = newGenerator().generate(SAMPLE_TEXT, resolved, capturedAt)

        assertEquals(1, observations.size)
        assertEquals(ObservationEvidence.VOLUNTEERED_CONTEXT, observations.first().evidence)
        assertTrue(observations.first().text.contains("goblin hours"))
        verify(exactly = 0) { engine.streamText(any(), any()) }
    }

    @Test
    fun `5am capture is outside goblin hours and falls through to the model`() = runTest {
        val resolved = ResolvedExtraction(emptyMap())
        val capturedAt = ZonedDateTime.of(2026, 5, 11, 5, 0, 0, 0, ZoneId.of("America/New_York"))
        every { engine.streamText(any(), any()) } returns flowOf(
            """{"observations":[{"text":"Three boss mentions.","evidence":"theme-noticing","fields":["tags"]}]}""",
        )

        val observations = newGenerator().generate(SAMPLE_TEXT, resolved, capturedAt)

        assertEquals(1, observations.size)
        assertEquals(ObservationEvidence.THEME_NOTICING, observations.first().evidence)
        verify(exactly = 1) { engine.streamText(any(), any()) }
    }

    @Test
    fun `falls back to model when no deterministic signal is present`() = runTest {
        val resolved = ResolvedExtraction(emptyMap())
        every { engine.streamText(any(), any()) } returns
            flowOf(themeNoticingPayload("You logged three boss mentions."))

        val observations = newGenerator().generate(SAMPLE_TEXT, resolved, SAMPLE_DAY)

        assertEquals(1, observations.size)
        assertEquals(ObservationEvidence.THEME_NOTICING, observations.first().evidence)
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
    fun `deterministic commitment wins and skips the model call`() = runTest {
        val resolved = ResolvedExtraction(
            mapOf(
                "stated_commitment" to ResolvedField(
                    mapOf("text" to "send the doc"),
                    ConfidenceVerdict.CANONICAL,
                ),
            ),
        )

        val observations = newGenerator().generate(SAMPLE_TEXT, resolved, SAMPLE_DAY)

        assertEquals(1, observations.size)
        assertEquals(ObservationEvidence.COMMITMENT_FLAG, observations[0].evidence)
        verify(exactly = 0) { engine.streamText(any(), any()) }
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
    fun `commitment without topic_or_person emits the no-topic line shape`() = runTest {
        val resolved = ResolvedExtraction(
            mapOf(
                "stated_commitment" to ResolvedField(
                    mapOf("text" to "ship the doc"),
                    ConfidenceVerdict.CANONICAL,
                ),
            ),
        )

        val observations = newGenerator().generate(SAMPLE_TEXT, resolved, SAMPLE_DAY)

        assertEquals(1, observations.size)
        assertTrue(observations.first().text.contains("ship the doc"))
        assertEquals(false, observations.first().text.contains("re:"))
        verify(exactly = 0) { engine.streamText(any(), any()) }
    }

    @Test
    fun `commitment with blank text falls through to other deterministic paths`() = runTest {
        val resolved = ResolvedExtraction(
            mapOf(
                "stated_commitment" to ResolvedField(
                    mapOf("text" to "   ", "topic_or_person" to "Nora"),
                    ConfidenceVerdict.CANONICAL,
                ),
            ),
        )
        // No vocab, no goblin — model fallback should fire.
        every { engine.streamText(any(), any()) } returns flowOf(themeNoticingPayload("Theme noted."))

        val observations = newGenerator().generate(SAMPLE_TEXT, resolved, SAMPLE_DAY)

        assertEquals(1, observations.size)
        assertEquals(ObservationEvidence.THEME_NOTICING, observations.first().evidence)
        verify(exactly = 1) { engine.streamText(any(), any()) }
    }

    @Test
    fun `model fallback renders all resolved field value shapes into the prompt`() = runTest {
        val resolved = ResolvedExtraction(
            mapOf(
                "tags" to ResolvedField(listOf("focus", "long-stretch"), ConfidenceVerdict.CANONICAL),
                "template_label" to ResolvedField("locked-in", ConfidenceVerdict.CANONICAL_WITH_CONFLICT),
                "recurrence_link" to ResolvedField(null, ConfidenceVerdict.AMBIGUOUS),
                "state_shift" to ResolvedField(true, ConfidenceVerdict.CANONICAL),
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
        assertTrue(prompt.contains("state_shift"), "prompt should render boolean")
        assertTrue(prompt.contains("a=1"), "prompt should render nested map")
    }

    @Test
    fun `model fallback with empty resolved fields renders the no-fields sentinel`() = runTest {
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
        // 2026-05-11 14:00 America/New_York — outside the goblin-hours window.
        private val SAMPLE_DAY: ZonedDateTime =
            ZonedDateTime.of(2026, 5, 11, 14, 0, 0, 0, ZoneId.of("America/New_York"))
        private const val SAMPLE_TEXT = "Standup ran long again. The doc is still not sent."
    }
}
