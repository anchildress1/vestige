package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.DetectedPattern
import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.Persona
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PatternTitleGeneratorTest {

    private val engine: LiteRtLmEngine = mockk()
    private val dispatcher = UnconfinedTestDispatcher()

    private fun newGenerator(forbidden: (String) -> Boolean = { false }) = PatternTitleGenerator(
        engine = engine,
        personaPromptComposer = { "PERSONA" },
        templateLoader = { "TEMPLATE" },
        forbiddenPhraseDetector = forbidden,
        ioDispatcher = dispatcher,
    )

    private fun samplePattern(): DetectedPattern = DetectedPattern(
        patternId = "deadbeef".repeat(8),
        kind = PatternKind.TEMPLATE_RECURRENCE,
        signatureJson = "{\"kind\":\"template_recurrence\",\"label\":\"aftermath\"}",
        templateLabel = "aftermath",
        supportingEntryIds = listOf(1L, 2L, 3L),
        firstSeenTimestamp = 1_000L,
        lastSeenTimestamp = 2_000L,
    )

    @Test
    fun `returns trimmed model output when valid`() = runTest {
        coEvery { engine.generateText(any()) } returns "Tuesday Meetings"
        val out = newGenerator().generate(Persona.WITNESS, samplePattern())
        assertEquals("Tuesday Meetings", out)
    }

    @Test
    fun `strips surrounding quotes and code fences`() = runTest {
        coEvery { engine.generateText(any()) } returns "\"Goblin Hours\""
        assertEquals("Goblin Hours", newGenerator().generate(Persona.HARDASS, samplePattern()))
    }

    @Test
    fun `truncates to 24 chars on word boundary`() = runTest {
        coEvery { engine.generateText(any()) } returns "Way Too Long Of A Pattern Title For This"
        val out = newGenerator().generate(Persona.EDITOR, samplePattern())
        assertNotNull(out)
        assertTrue(out!!.length <= PatternTitleGenerator.MAX_TITLE_CHARS, "got '$out' (${out.length} chars)")
    }

    @Test
    fun `takes only first line — model run-on is discarded`() = runTest {
        coEvery { engine.generateText(any()) } returns "Aftermath Loop\nThis pattern means..."
        assertEquals("Aftermath Loop", newGenerator().generate(Persona.WITNESS, samplePattern()))
    }

    @Test
    fun `returns null when output is empty`() = runTest {
        coEvery { engine.generateText(any()) } returns "   "
        assertNull(newGenerator().generate(Persona.WITNESS, samplePattern()))
    }

    @Test
    fun `returns null when forbidden phrase detected`() = runTest {
        coEvery { engine.generateText(any()) } returns "Try This Now"
        val out = newGenerator(forbidden = { it.lowercase().contains("try") })
            .generate(Persona.WITNESS, samplePattern())
        assertNull(out)
    }

    @Test
    fun `swallows engine exceptions and returns null`() = runTest {
        coEvery { engine.generateText(any()) } throws RuntimeException("oom")
        assertNull(newGenerator().generate(Persona.WITNESS, samplePattern()))
    }

    @Test
    fun `strips disallowed punctuation per prompt contract`() = runTest {
        // ADR-compliant titles forbid punctuation except an optional hyphen. A model returning
        // `Aftermath Loop!` must not persist with the trailing exclamation.
        coEvery { engine.generateText(any()) } returns "Aftermath Loop!"
        assertEquals("Aftermath Loop", newGenerator().generate(Persona.WITNESS, samplePattern()))
    }

    @Test
    fun `preserves hyphen but drops periods commas semicolons`() = runTest {
        coEvery { engine.generateText(any()) } returns "Tuesday-Loop, Again."
        assertEquals("Tuesday-Loop Again", newGenerator().generate(Persona.WITNESS, samplePattern()))
    }

    @Test
    fun `strips fenced block with language tag`() = runTest {
        // The prior sanitizer only matched bare ```; a language-tagged fence
        // (```text\nAftermath\n```) kept the language token as the first line and persisted
        // it. Now the whole fence wrapper is dropped — inner content survives.
        coEvery { engine.generateText(any()) } returns "```text\nAftermath Loop\n```"
        assertEquals("Aftermath Loop", newGenerator().generate(Persona.WITNESS, samplePattern()))
    }

    @Test
    fun `strips bare triple-backtick fence (regression of original case)`() = runTest {
        coEvery { engine.generateText(any()) } returns "```\nGoblin Hours\n```"
        assertEquals("Goblin Hours", newGenerator().generate(Persona.WITNESS, samplePattern()))
    }
}
