package dev.anchildress1.vestige.inference

import com.google.ai.edge.litertlm.Content
import dev.anchildress1.vestige.model.Persona
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ForegroundInferenceTest {

    private val completedAt: Instant = Instant.parse("2026-05-09T12:00:00Z")
    private val fixedClock: Clock = Clock.fixed(completedAt, ZoneOffset.UTC)

    private fun rawSuccess(transcription: String, followUp: String): String =
        "## TRANSCRIPTION\n$transcription\n\n## FOLLOW_UP\n$followUp\n"

    private fun samples(): FloatArray = floatArrayOf(0.1f, -0.1f, 0.0f, 0.5f, -0.5f)

    private fun audioChunk(): AudioChunk = AudioChunk(
        samples = samples(),
        sampleRateHz = AudioCapture.SAMPLE_RATE_HZ,
        isFinal = true,
    )

    @Test
    fun `runForegroundCall returns Success when engine response parses cleanly`(@TempDir cacheDir: File) = runTest {
        val engine = mockk<LiteRtLmEngine>()
        coEvery { engine.sendMessageContents(any()) } returns rawSuccess(
            transcription = "I sat in the chair for an hour.",
            followUp = "What were you trying to do before that?",
        )

        val result = ForegroundInference(engine, cacheDir, clock = fixedClock).runForegroundCall(
            audio = audioChunk(),
            transcript = Transcript(),
            persona = Persona.WITNESS,
        )

        val success = assertInstanceOf(ForegroundResult.Success::class.java, result)
        assertAll(
            { assertEquals("I sat in the chair for an hour.", success.transcription) },
            { assertEquals("What were you trying to do before that?", success.followUp) },
            { assertEquals(Persona.WITNESS, success.persona) },
            { assertEquals(completedAt, success.completedAt) },
            { assertTrue(success.elapsedMs >= 0L, "elapsedMs should be non-negative, was ${success.elapsedMs}") },
        )
    }

    @Test
    fun `parse failure surfaces as ParseFailure rather than throwing or retrying`(@TempDir cacheDir: File) = runTest {
        val engine = mockk<LiteRtLmEngine>()
        coEvery { engine.sendMessageContents(any()) } returns "no headers, just prose"

        val result = ForegroundInference(engine, cacheDir, clock = fixedClock).runForegroundCall(
            audio = audioChunk(),
            transcript = Transcript(),
            persona = Persona.HARDASS,
        )

        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, result)
        assertEquals(ForegroundResult.ParseReason.MISSING_TRANSCRIPTION, failure.reason)
        assertEquals(Persona.HARDASS, failure.persona)
        assertEquals("no headers, just prose", failure.rawResponse)
        // Only one engine call — no silent retry per ADR-002 §"Structured-output reliability".
        coVerify(exactly = 1) { engine.sendMessageContents(any()) }
    }

    @Test
    fun `composed prompt embeds persona shared rules and the active persona tag`(@TempDir cacheDir: File) = runTest {
        val engine = mockk<LiteRtLmEngine>()
        val captured = slot<List<Content>>()
        coEvery { engine.sendMessageContents(capture(captured)) } returns rawSuccess("a", "b")

        ForegroundInference(engine, cacheDir, clock = fixedClock).runForegroundCall(
            audio = audioChunk(),
            transcript = Transcript(),
            persona = Persona.EDITOR,
        )

        val systemPrompt = (captured.captured[0] as Content.Text).text
        assertAll(
            { assertTrue(systemPrompt.contains("Persona: Editor"), "Missing persona tag in prompt") },
            { assertTrue(systemPrompt.contains("cognition tracker"), "Missing shared sentinel in prompt") },
            { assertTrue(systemPrompt.contains(ForegroundInference.RECENT_TURNS_HEADER)) },
            { assertTrue(systemPrompt.contains("## TRANSCRIPTION")) },
            { assertTrue(systemPrompt.contains("## FOLLOW_UP")) },
        )
    }

    @Test
    fun `empty transcript composes the no-prior-turns sentinel`(@TempDir cacheDir: File) = runTest {
        val engine = mockk<LiteRtLmEngine>()
        val captured = slot<List<Content>>()
        coEvery { engine.sendMessageContents(capture(captured)) } returns rawSuccess("a", "b")

        ForegroundInference(engine, cacheDir, clock = fixedClock).runForegroundCall(
            audio = audioChunk(),
            transcript = Transcript(),
            persona = Persona.WITNESS,
        )

        val systemPrompt = (captured.captured[0] as Content.Text).text
        assertTrue(systemPrompt.contains(ForegroundInference.NO_RECENT_TURNS))
    }

    @Test
    fun `transcript is included oldest-first and capped at historyTurnLimit`(@TempDir cacheDir: File) = runTest {
        val engine = mockk<LiteRtLmEngine>()
        val captured = slot<List<Content>>()
        coEvery { engine.sendMessageContents(capture(captured)) } returns rawSuccess("a", "b")

        val transcript = Transcript().apply {
            // 6 alternating turns; only the last 4 should appear.
            append(Turn(Speaker.USER, "u-old", Instant.parse("2026-05-09T11:55:00Z")))
            append(Turn(Speaker.MODEL, "m-old", Instant.parse("2026-05-09T11:55:01Z"), Persona.WITNESS))
            append(Turn(Speaker.USER, "u-1", Instant.parse("2026-05-09T11:56:00Z")))
            append(Turn(Speaker.MODEL, "m-1", Instant.parse("2026-05-09T11:56:01Z"), Persona.HARDASS))
            append(Turn(Speaker.USER, "u-2", Instant.parse("2026-05-09T11:57:00Z")))
            append(Turn(Speaker.MODEL, "m-2", Instant.parse("2026-05-09T11:57:01Z"), Persona.EDITOR))
        }

        ForegroundInference(engine, cacheDir, clock = fixedClock).runForegroundCall(
            audio = audioChunk(),
            transcript = transcript,
            persona = Persona.HARDASS,
        )

        val systemPrompt = (captured.captured[0] as Content.Text).text
        val turnsBlockStart = systemPrompt.indexOf(ForegroundInference.RECENT_TURNS_HEADER)
        val outputBlockStart = systemPrompt.indexOf("## OUTPUT FORMAT")
        val turnsBlock = systemPrompt.substring(turnsBlockStart, outputBlockStart)

        assertAll(
            { assertFalse(turnsBlock.contains("u-old"), "Oldest USER turn should be dropped") },
            { assertFalse(turnsBlock.contains("m-old"), "Oldest MODEL turn should be dropped") },
            { assertTrue(turnsBlock.contains("- USER: u-1")) },
            { assertTrue(turnsBlock.contains("- MODEL: m-1")) },
            { assertTrue(turnsBlock.contains("- USER: u-2")) },
            { assertTrue(turnsBlock.contains("- MODEL: m-2")) },
        )
        // Order check — u-1 must precede m-2.
        assertTrue(turnsBlock.indexOf("- USER: u-1") < turnsBlock.indexOf("- MODEL: m-2"))
    }

    @Test
    fun `audio handoff goes through Content_AudioFile pointing inside cacheDir`(@TempDir cacheDir: File) = runTest {
        val engine = mockk<LiteRtLmEngine>()
        val captured = slot<List<Content>>()
        coEvery { engine.sendMessageContents(capture(captured)) } returns rawSuccess("a", "b")

        ForegroundInference(engine, cacheDir, clock = fixedClock).runForegroundCall(
            audio = audioChunk(),
            transcript = Transcript(),
            persona = Persona.WITNESS,
        )

        val parts = captured.captured
        assertAll(
            { assertEquals(2, parts.size) },
            { assertInstanceOf(Content.Text::class.java, parts[0]) },
            { assertInstanceOf(Content.AudioFile::class.java, parts[1]) },
        )
        // The temp WAV path must have lived inside cacheDir during the call. We can't read it
        // back (deleted in finally), but we can verify the path prefix.
        val audioPath = (parts[1] as Content.AudioFile).absolutePath
        assertTrue(
            audioPath.startsWith(cacheDir.absolutePath),
            "Temp WAV path $audioPath must live inside cacheDir ${cacheDir.absolutePath}",
        )
    }

    @Test
    fun `temp WAV is deleted after a successful engine call`(@TempDir cacheDir: File) = runTest {
        val engine = mockk<LiteRtLmEngine>()
        coEvery { engine.sendMessageContents(any()) } returns rawSuccess("a", "b")

        ForegroundInference(engine, cacheDir, clock = fixedClock).runForegroundCall(
            audio = audioChunk(),
            transcript = Transcript(),
            persona = Persona.WITNESS,
        )

        assertEquals(0, cacheDir.listFiles().orEmpty().size, "Temp WAV must be deleted after the call")
    }

    @Test
    fun `temp WAV is deleted even when the engine throws`(@TempDir cacheDir: File) = runTest {
        val engine = mockk<LiteRtLmEngine>()
        coEvery { engine.sendMessageContents(any()) } throws RuntimeException("model crashed")
        val inference = ForegroundInference(engine, cacheDir, clock = fixedClock)

        val caught = runCatching {
            inference.runForegroundCall(audioChunk(), Transcript(), Persona.WITNESS)
        }.exceptionOrNull()

        assertInstanceOf(RuntimeException::class.java, caught)
        assertEquals("model crashed", caught?.message)
        assertEquals(0, cacheDir.listFiles().orEmpty().size, "Temp WAV must be deleted on engine error")
    }

    @Test
    fun `empty audio samples are rejected before the engine is called`(@TempDir cacheDir: File) {
        val engine = mockk<LiteRtLmEngine>()
        val inference = ForegroundInference(engine, cacheDir, clock = fixedClock)
        assertThrows(IllegalArgumentException::class.java) {
            runTest {
                inference.runForegroundCall(
                    audio = AudioChunk(FloatArray(0), AudioCapture.SAMPLE_RATE_HZ, isFinal = true),
                    transcript = Transcript(),
                    persona = Persona.WITNESS,
                )
            }
        }
    }

    @Test
    fun `missing cacheDir is rejected before the engine is called`(@TempDir cacheDir: File) {
        val engine = mockk<LiteRtLmEngine>()
        val nonExistent = File(cacheDir, "nope")
        val inference = ForegroundInference(engine, nonExistent, clock = fixedClock)
        assertThrows(IllegalArgumentException::class.java) {
            runTest {
                inference.runForegroundCall(audioChunk(), Transcript(), Persona.WITNESS)
            }
        }
    }

    @Test
    fun `historyTurnLimit must be positive`(@TempDir cacheDir: File) {
        val engine = mockk<LiteRtLmEngine>()
        assertThrows(IllegalArgumentException::class.java) {
            ForegroundInference(engine, cacheDir, historyTurnLimit = 0, clock = fixedClock)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForegroundInference(engine, cacheDir, historyTurnLimit = -1, clock = fixedClock)
        }
    }

    @Test
    fun `empty engine response surfaces as EMPTY_RESPONSE failure`(@TempDir cacheDir: File) = runTest {
        val engine = mockk<LiteRtLmEngine>()
        coEvery { engine.sendMessageContents(any()) } returns ""

        val result = ForegroundInference(engine, cacheDir, clock = fixedClock).runForegroundCall(
            audio = audioChunk(),
            transcript = Transcript(),
            persona = Persona.WITNESS,
        )

        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, result)
        assertEquals(ForegroundResult.ParseReason.EMPTY_RESPONSE, failure.reason)
    }
}
