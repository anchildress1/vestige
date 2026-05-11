package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.ConfidenceVerdict
import dev.anchildress1.vestige.model.EntryObservation
import dev.anchildress1.vestige.model.ObservationEvidence
import dev.anchildress1.vestige.model.ResolvedExtraction
import dev.anchildress1.vestige.model.ResolvedField
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        coVerify(exactly = 0) { engine.generateText(any()) }
    }

    @Test
    fun `vocabulary contradiction routes to deterministic observation without model call`() = runTest {
        val resolved = ResolvedExtraction(
            mapOf(
                "vocabulary_contradictions" to ResolvedField(
                    listOf(
                        mapOf("term_a" to "fine", "term_b" to "flattened", "snippet" to "ran long again"),
                    ),
                    ConfidenceVerdict.CANONICAL,
                ),
            ),
        )

        val observations = newGenerator().generate(SAMPLE_TEXT, resolved, SAMPLE_DAY)

        assertEquals(1, observations.size)
        val obs = observations.first()
        assertEquals(ObservationEvidence.VOCABULARY_CONTRADICTION, obs.evidence)
        assertTrue(obs.text.contains("fine"))
        assertTrue(obs.text.contains("flattened"))
        coVerify(exactly = 0) { engine.generateText(any()) }
    }

    @Test
    fun `goblin hours capture produces a volunteered-context observation when no other signal exists`() = runTest {
        val resolved = ResolvedExtraction(emptyMap())
        val capturedAt = ZonedDateTime.of(2026, 5, 11, 3, 14, 0, 0, ZoneId.of("America/New_York"))

        val observations = newGenerator().generate(SAMPLE_TEXT, resolved, capturedAt)

        assertEquals(1, observations.size)
        assertEquals(ObservationEvidence.VOLUNTEERED_CONTEXT, observations.first().evidence)
        assertTrue(observations.first().text.contains("goblin hours"))
        coVerify(exactly = 0) { engine.generateText(any()) }
    }

    @Test
    fun `5am capture is outside goblin hours and falls through to the model`() = runTest {
        val resolved = ResolvedExtraction(emptyMap())
        val capturedAt = ZonedDateTime.of(2026, 5, 11, 5, 0, 0, 0, ZoneId.of("America/New_York"))
        coEvery { engine.generateText(any()) } returns
            """{"observations":[{"text":"Three boss mentions.","evidence":"theme-noticing","fields":["tags"]}]}"""

        val observations = newGenerator().generate(SAMPLE_TEXT, resolved, capturedAt)

        assertEquals(1, observations.size)
        assertEquals(ObservationEvidence.THEME_NOTICING, observations.first().evidence)
        coVerify(exactly = 1) { engine.generateText(any()) }
    }

    @Test
    fun `falls back to model when no deterministic signal is present`() = runTest {
        val resolved = ResolvedExtraction(emptyMap())
        coEvery { engine.generateText(any()) } returns themeNoticingPayload("You logged three boss mentions.")

        val observations = newGenerator().generate(SAMPLE_TEXT, resolved, SAMPLE_DAY)

        assertEquals(1, observations.size)
        assertEquals(ObservationEvidence.THEME_NOTICING, observations.first().evidence)
    }

    @Test
    fun `retries the model once when the first response contains a forbidden phrase`() = runTest {
        val resolved = ResolvedExtraction(emptyMap())
        coEvery { engine.generateText(any()) } returnsMany listOf(
            """{"observations":[{"text":"You might be feeling worn out.","evidence":"theme-noticing","fields":[]}]}""",
            """{"observations":[{"text":"Three boss mentions.","evidence":"theme-noticing","fields":["tags"]}]}""",
        )

        val observations = newGenerator().generate(SAMPLE_TEXT, resolved, SAMPLE_DAY)

        assertEquals(1, observations.size)
        assertEquals("Three boss mentions.", observations.first().text)
        coVerify(exactly = 2) { engine.generateText(any()) }
    }

    @Test
    fun `returns empty list when both model attempts violate the voice rules`() = runTest {
        val resolved = ResolvedExtraction(emptyMap())
        coEvery { engine.generateText(any()) } returns
            """{"observations":[{"text":"It seems you're avoiding things.","evidence":"theme-noticing","fields":[]}]}"""

        val observations = newGenerator().generate(SAMPLE_TEXT, resolved, SAMPLE_DAY)

        assertTrue(observations.isEmpty())
        coVerify(exactly = 2) { engine.generateText(any()) }
    }

    @Test
    fun `returns empty list when model throws on both attempts`() = runTest {
        val resolved = ResolvedExtraction(emptyMap())
        coEvery { engine.generateText(any()) } throws RuntimeException("native crash")

        val observations = newGenerator().generate(SAMPLE_TEXT, resolved, SAMPLE_DAY)

        assertTrue(observations.isEmpty())
        coVerify(exactly = 2) { engine.generateText(any()) }
    }

    @Test
    fun `deterministic commitment plus vocabulary-contradiction both land, capped at two`() = runTest {
        val resolved = ResolvedExtraction(
            mapOf(
                "stated_commitment" to ResolvedField(
                    mapOf("text" to "send the doc"),
                    ConfidenceVerdict.CANONICAL,
                ),
                "vocabulary_contradictions" to ResolvedField(
                    listOf(mapOf("term_a" to "fine", "term_b" to "stuck")),
                    ConfidenceVerdict.CANONICAL,
                ),
            ),
        )

        val observations = newGenerator().generate(SAMPLE_TEXT, resolved, SAMPLE_DAY)

        assertEquals(2, observations.size)
        assertEquals(ObservationEvidence.COMMITMENT_FLAG, observations[0].evidence)
        assertEquals(ObservationEvidence.VOCABULARY_CONTRADICTION, observations[1].evidence)
        coVerify(exactly = 0) { engine.generateText(any()) }
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
        coVerify(exactly = 0) { engine.generateText(any()) }
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
