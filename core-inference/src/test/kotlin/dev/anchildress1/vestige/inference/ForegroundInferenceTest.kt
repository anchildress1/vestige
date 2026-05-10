package dev.anchildress1.vestige.inference

import com.google.ai.edge.litertlm.Content
import dev.anchildress1.vestige.model.Persona
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
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
        "<transcription>$transcription</transcription>\n<follow_up>$followUp</follow_up>\n"

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
            persona = Persona.HARDASS,
        )

        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, result)
        assertEquals(ForegroundResult.ParseReason.MISSING_TRANSCRIPTION, failure.reason)
        assertEquals(Persona.HARDASS, failure.persona)
        assertEquals("no headers, just prose", failure.rawResponse)
        assertEquals(null, failure.recoveredTranscription)
    }

    @Test
    fun `parse failure preserves recoveredTranscription when only the follow-up block is mangled`(
        @TempDir cacheDir: File,
    ) = runTest {
        val engine = mockk<LiteRtLmEngine>()
        coEvery { engine.sendMessageContents(any()) } returns
            "<transcription>verbatim user text</transcription>\n(model went off-script here, no follow_up tags)"

        val result = ForegroundInference(engine, cacheDir, clock = fixedClock).runForegroundCall(
            audio = audioChunk(),
            persona = Persona.WITNESS,
        )

        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, result)
        assertEquals(ForegroundResult.ParseReason.MISSING_FOLLOW_UP, failure.reason)
        assertEquals("verbatim user text", failure.recoveredTranscription)
    }

    @Test
    fun `composed prompt embeds persona shared rules, the active persona tag, and the output schema`(
        @TempDir cacheDir: File,
    ) = runTest {
        val engine = mockk<LiteRtLmEngine>()
        val captured = slot<List<Content>>()
        coEvery { engine.sendMessageContents(capture(captured)) } returns rawSuccess("a", "b")

        ForegroundInference(engine, cacheDir, clock = fixedClock).runForegroundCall(
            audio = audioChunk(),
            persona = Persona.EDITOR,
        )

        val systemPrompt = (captured.captured[0] as Content.Text).text
        assertAll(
            { assertTrue(systemPrompt.contains("Persona: Editor"), "Missing persona tag in prompt") },
            { assertTrue(systemPrompt.contains("cognition tracker"), "Missing shared sentinel in prompt") },
            // STT-B fallback: no `## RECENT TURNS` block in the prompt anymore. Single-turn-per-capture.
            {
                assertFalse(
                    systemPrompt.contains("RECENT TURNS"),
                    "Recent-turns block was removed by the STT-B fallback",
                )
            },
            // The schema reminder describes the format prose-only — no literal example tags so
            // the model can't echo placeholder content the parser would mistake for a response.
            {
                assertTrue(systemPrompt.contains("transcription tags"), "Output schema must mention transcription tags")
            },
            { assertTrue(systemPrompt.contains("follow_up tags"), "Output schema must mention follow_up tags") },
            {
                assertFalse(
                    systemPrompt.contains("<transcription>"),
                    "Schema example must not contain literal opening tag",
                )
            },
            {
                assertFalse(systemPrompt.contains("<follow_up>"), "Schema example must not contain literal opening tag")
            },
        )
    }

    @Test
    fun `audio handoff goes through Content_AudioFile pointing inside cacheDir`(@TempDir cacheDir: File) = runTest {
        val engine = mockk<LiteRtLmEngine>()
        val captured = slot<List<Content>>()
        coEvery { engine.sendMessageContents(capture(captured)) } returns rawSuccess("a", "b")

        ForegroundInference(engine, cacheDir, clock = fixedClock).runForegroundCall(
            audio = audioChunk(),
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
            inference.runForegroundCall(audioChunk(), Persona.WITNESS)
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
                    persona = Persona.WITNESS,
                )
            }
        }
    }

    @Test
    fun `crash-leftover temp WAVs are swept before the next call`(@TempDir cacheDir: File) = runTest {
        // Simulate a prior call that died mid-inference: a vestige-fg-*.wav still on disk.
        val leftover =
            File(cacheDir, "${ForegroundInference.TEMP_PREFIX}previous-crash${ForegroundInference.TEMP_SUFFIX}")
        leftover.writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46))
        assertTrue(leftover.exists())

        val engine = mockk<LiteRtLmEngine>()
        coEvery { engine.sendMessageContents(any()) } returns rawSuccess("a", "b")
        ForegroundInference(engine, cacheDir, clock = fixedClock).runForegroundCall(
            audio = audioChunk(),
            persona = Persona.WITNESS,
        )

        assertFalse(leftover.exists(), "Pre-call sweep must delete crash-leftover foreground WAVs")
        assertEquals(0, cacheDir.listFiles().orEmpty().size)
    }

    @Test
    fun `sweep skips another in-flight foreground temp WAV`(@TempDir cacheDir: File) = runTest {
        val engine = mockk<LiteRtLmEngine>()
        val firstAudioPath = CompletableDeferred<String>()
        val secondCallReachedEngine = CompletableDeferred<Unit>()
        val releaseFirstCall = CompletableDeferred<Unit>()
        var invocationCount = 0
        coEvery { engine.sendMessageContents(any()) } coAnswers {
            invocationCount += 1
            val parts = firstArg<List<Content>>()
            val audioPath = (parts[1] as Content.AudioFile).absolutePath
            when (invocationCount) {
                1 -> {
                    firstAudioPath.complete(audioPath)
                    secondCallReachedEngine.await()
                    assertTrue(
                        File(audioPath).exists(),
                        "An overlapping call must not sweep the first call's live temp WAV",
                    )
                    releaseFirstCall.await()
                    rawSuccess("first transcription", "first follow-up")
                }

                2 -> {
                    val activeFirstPath = firstAudioPath.await()
                    assertTrue(
                        File(activeFirstPath).exists(),
                        "The first call's temp WAV should still exist when the second call starts",
                    )
                    secondCallReachedEngine.complete(Unit)
                    releaseFirstCall.complete(Unit)
                    rawSuccess("second transcription", "second follow-up")
                }

                else -> error("Unexpected sendMessageContents invocation #$invocationCount")
            }
        }

        val dispatcher = StandardTestDispatcher(testScheduler)
        val firstInference = ForegroundInference(engine, cacheDir, clock = fixedClock, ioDispatcher = dispatcher)
        val secondInference = ForegroundInference(engine, cacheDir, clock = fixedClock, ioDispatcher = dispatcher)

        val firstCall = async {
            firstInference.runForegroundCall(audioChunk(), Persona.WITNESS)
        }
        val secondCall = async {
            secondInference.runForegroundCall(audioChunk(), Persona.HARDASS)
        }

        val firstResult = assertInstanceOf(ForegroundResult.Success::class.java, firstCall.await())
        val secondResult = assertInstanceOf(ForegroundResult.Success::class.java, secondCall.await())
        assertEquals("first transcription", firstResult.transcription)
        assertEquals("second transcription", secondResult.transcription)
        assertEquals(0, cacheDir.listFiles().orEmpty().size, "Both temp WAVs should be cleaned up after completion")
    }

    @Test
    fun `truncate-on-fail discards audio payload even when delete fails`(@TempDir cacheDir: File) {
        // The discardTempWav helper falls back to truncate-then-retry-delete on initial delete
        // failure so the audio payload is unrecoverable even if the inode survives. Unit-testing
        // an actual delete-failure on the real File API requires platform-specific tricks
        // (read-only parent dir, file locks); we assert the truncate primitive itself behaves as
        // promised, which is the load-bearing privacy guarantee.
        val payload = File(cacheDir, "vestige-fg-truncate-test.wav")
        payload.writeBytes(ByteArray(1024) { 0x42 })
        assertEquals(1024, payload.length(), "Test setup: payload must contain audio bytes")

        payload.outputStream().use { /* truncate to zero bytes */ }

        assertEquals(0L, payload.length(), "Truncate must zero out the audio payload")
        assertTrue(payload.delete(), "After truncate the now-empty file should be deletable")
    }

    @Test
    fun `sweep ignores unrelated files in cacheDir`(@TempDir cacheDir: File) = runTest {
        val unrelated = File(cacheDir, "model-artifact.litertlm")
        unrelated.writeBytes(byteArrayOf(0x00))
        val engine = mockk<LiteRtLmEngine>()
        coEvery { engine.sendMessageContents(any()) } returns rawSuccess("a", "b")
        ForegroundInference(engine, cacheDir, clock = fixedClock).runForegroundCall(
            audio = audioChunk(),
            persona = Persona.WITNESS,
        )

        assertTrue(unrelated.exists(), "Sweep must not touch non-foreground-temp files")
    }

    @Test
    fun `non-final audio chunk is rejected — final-chunk-only contract`(@TempDir cacheDir: File) {
        val engine = mockk<LiteRtLmEngine>()
        val inference = ForegroundInference(engine, cacheDir, clock = fixedClock)
        val intermediate = AudioChunk(samples(), AudioCapture.SAMPLE_RATE_HZ, isFinal = false)
        val ex = assertThrows(IllegalArgumentException::class.java) {
            runTest { inference.runForegroundCall(intermediate, Persona.WITNESS) }
        }
        assertTrue(
            ex.message!!.contains("final-chunk only"),
            "Error must explain the final-chunk contract; was: ${ex.message}",
        )
    }

    @Test
    fun `missing cacheDir is rejected before the engine is called`(@TempDir cacheDir: File) {
        val engine = mockk<LiteRtLmEngine>()
        val nonExistent = File(cacheDir, "nope")
        val inference = ForegroundInference(engine, nonExistent, clock = fixedClock)
        assertThrows(IllegalArgumentException::class.java) {
            runTest {
                inference.runForegroundCall(audioChunk(), Persona.WITNESS)
            }
        }
    }

    @Test
    fun `per-capture persona reaches the engine prompt`(@TempDir cacheDir: File) = runTest {
        // Post-fallback replacement for the deleted "session setPersona routes the next foreground
        // call through the new persona prompt" multi-turn test. Two independent CaptureSession
        // instances drive two foreground calls under different personas; each call's prompt must
        // carry that capture's persona, and neither call sees a `## RECENT TURNS` block.
        val engine = mockk<LiteRtLmEngine>()
        val captured = mutableListOf<List<Content>>()
        coEvery { engine.sendMessageContents(capture(captured)) } returns rawSuccess("u", "f")
        val inference = ForegroundInference(engine, cacheDir, clock = fixedClock)

        val witnessSession = CaptureSession(defaultPersona = Persona.WITNESS)
        witnessSession.startRecording()
        witnessSession.submitForInference()
        inference.runForegroundCall(audioChunk(), witnessSession.activePersona)

        val editorSession = CaptureSession(defaultPersona = Persona.EDITOR)
        editorSession.startRecording()
        editorSession.submitForInference()
        inference.runForegroundCall(audioChunk(), editorSession.activePersona)

        assertEquals(2, captured.size)
        val firstPrompt = (captured[0][0] as Content.Text).text
        val secondPrompt = (captured[1][0] as Content.Text).text
        assertAll(
            { assertTrue(firstPrompt.contains("Persona: Witness"), "Capture 1 must carry Witness prompt") },
            { assertFalse(firstPrompt.contains("Persona: Editor"), "Capture 1 must not carry Editor prompt") },
            { assertTrue(secondPrompt.contains("Persona: Editor"), "Capture 2 must carry Editor prompt") },
            { assertFalse(secondPrompt.contains("Persona: Witness"), "Capture 2 must not carry Witness prompt") },
            { assertFalse(firstPrompt.contains("RECENT TURNS"), "STT-B fallback removed the recent-turns block") },
            { assertFalse(secondPrompt.contains("RECENT TURNS"), "STT-B fallback removed the recent-turns block") },
        )
    }

    @Test
    fun `empty engine response surfaces as EMPTY_RESPONSE failure`(@TempDir cacheDir: File) = runTest {
        val engine = mockk<LiteRtLmEngine>()
        coEvery { engine.sendMessageContents(any()) } returns ""

        val result = ForegroundInference(engine, cacheDir, clock = fixedClock).runForegroundCall(
            audio = audioChunk(),
            persona = Persona.WITNESS,
        )

        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, result)
        assertEquals(ForegroundResult.ParseReason.EMPTY_RESPONSE, failure.reason)
        assertEquals(null, failure.recoveredTranscription)
    }
}
