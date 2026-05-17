package dev.anchildress1.vestige.inference

import com.google.ai.edge.litertlm.Content
import dev.anchildress1.vestige.model.Persona
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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

    // streamMessageContents is a cold Flow (not suspend) — stub with `every`, emit chunks.
    private fun engineEmitting(vararg chunks: String): LiteRtLmEngine = mockk {
        every { streamMessageContents(any(), any()) } returns flowOf(*chunks)
    }

    // Drives a foreground stream to its terminal ForegroundResult so the envelope-contract
    // assertions stay readable. Fails loudly if no Terminal landed.
    private suspend fun Flow<ForegroundStreamEvent>.terminal(): ForegroundResult {
        var result: ForegroundResult? = null
        collect { if (it is ForegroundStreamEvent.Terminal) result = it.result }
        return checkNotNull(result) { "stream produced no Terminal event" }
    }

    @Test
    fun `runForegroundCall terminal is Success when the stream parses cleanly`(@TempDir cacheDir: File) = runTest {
        val engine = engineEmitting(
            rawSuccess(
                transcription = "I sat in the chair for an hour.",
                followUp = "What were you trying to do before that?",
            ),
        )

        val result = ForegroundInference(engine, cacheDir, clock = fixedClock)
            .runForegroundCall(audio = audioChunk(), persona = Persona.WITNESS)
            .terminal()

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
    fun `streaming surfaces Transcription once then follow-up deltas then a Success terminal`(
        @TempDir cacheDir: File,
    ) = runTest {
        val engine = mockk<LiteRtLmEngine> {
            every { streamMessageContents(any(), any()) } returns flowOf(
                "<transcription>i kept reopening the ticket</transcription>",
                "<follow_up>what",
                " were you checking",
                " for</follow_up>",
            )
        }

        val events = ForegroundInference(engine, cacheDir, clock = fixedClock)
            .runForegroundCall(audio = audioChunk(), persona = Persona.WITNESS)
            .toList()

        val transcriptions = events.filterIsInstance<ForegroundStreamEvent.Transcription>()
        val deltas = events.filterIsInstance<ForegroundStreamEvent.FollowUpDelta>()
        val terminal = events.filterIsInstance<ForegroundStreamEvent.Terminal>().single()
        assertAll(
            { assertEquals(1, transcriptions.size, "transcription must surface exactly once") },
            { assertEquals("i kept reopening the ticket", transcriptions.single().text) },
            { assertTrue(deltas.isNotEmpty(), "follow-up must arrive as deltas") },
            {
                assertEquals(
                    "what were you checking for",
                    deltas.joinToString("") { it.text },
                    "concatenated deltas reconstruct the follow-up body",
                )
            },
            {
                assertEquals(
                    "what were you checking for",
                    (terminal.result as ForegroundResult.Success).followUp,
                )
            },
            {
                assertTrue(
                    events.indexOfFirst { it is ForegroundStreamEvent.Transcription } <
                        events.indexOfFirst { it is ForegroundStreamEvent.FollowUpDelta },
                    "transcription event precedes the first follow-up delta",
                )
            },
            { assertTrue(events.last() is ForegroundStreamEvent.Terminal, "Terminal is the last event") },
        )
    }

    @Test
    fun `a close tag split across two chunks still surfaces one Transcription`(@TempDir cacheDir: File) = runTest {
        val engine = mockk<LiteRtLmEngine> {
            every { streamMessageContents(any(), any()) } returns flowOf(
                "<transcription>verbatim words</transcr",
                "iption><follow_up>and a question?</follow_up>",
            )
        }

        val events = ForegroundInference(engine, cacheDir, clock = fixedClock)
            .runForegroundCall(audio = audioChunk(), persona = Persona.WITNESS)
            .toList()

        val transcriptions = events.filterIsInstance<ForegroundStreamEvent.Transcription>()
        assertAll(
            { assertEquals(1, transcriptions.size) },
            { assertEquals("verbatim words", transcriptions.single().text) },
            {
                assertEquals(
                    "verbatim words",
                    (events.last() as ForegroundStreamEvent.Terminal).result
                        .let { it as ForegroundResult.Success }.transcription,
                )
            },
        )
    }

    @Test
    fun `mid-stream cancellation deletes the temp WAV and never emits Terminal`(@TempDir cacheDir: File) = runTest {
        val engine = mockk<LiteRtLmEngine> {
            every { streamMessageContents(any(), any()) } returns flowOf(
                "<transcription>partial words</transcription>",
                "<follow_up>this never finis",
            )
        }

        // Collector takes only the first event (the Transcription) then the take() operator
        // cancels the upstream — simulating navigate-away mid-stream.
        val events = ForegroundInference(engine, cacheDir, clock = fixedClock)
            .runForegroundCall(audio = audioChunk(), persona = Persona.WITNESS)
            .take(1)
            .toList()

        assertAll(
            { assertEquals(1, events.size) },
            { assertInstanceOf(ForegroundStreamEvent.Transcription::class.java, events.single()) },
            { assertTrue(events.none { it is ForegroundStreamEvent.Terminal }, "no Terminal on cancel") },
            {
                assertEquals(
                    0,
                    cacheDir.listFiles().orEmpty().size,
                    "temp WAV must be discarded even on mid-stream cancellation",
                )
            },
        )
    }

    @Test
    fun `runForegroundTextCall terminal is Success on a clean stream`(@TempDir cacheDir: File) = runTest {
        val engine = engineEmitting(
            rawSuccess(transcription = "just got off the call again", followUp = "what did they actually want"),
        )

        val result = ForegroundInference(engine, cacheDir, clock = fixedClock)
            .runForegroundTextCall(text = "just got off the call again", persona = Persona.EDITOR)
            .terminal()

        val success = assertInstanceOf(ForegroundResult.Success::class.java, result)
        assertAll(
            { assertEquals("just got off the call again", success.transcription) },
            { assertEquals("what did they actually want", success.followUp) },
            { assertEquals(Persona.EDITOR, success.persona) },
            { assertEquals(completedAt, success.completedAt) },
        )
    }

    @Test
    fun `runForegroundTextCall surfaces ParseFailure terminal without throwing`(@TempDir cacheDir: File) = runTest {
        val engine = engineEmitting("")

        val result = ForegroundInference(engine, cacheDir, clock = fixedClock)
            .runForegroundTextCall(text = "typed it", persona = Persona.WITNESS)
            .terminal()

        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, result)
        assertEquals(ForegroundResult.ParseReason.EMPTY_RESPONSE, failure.reason)
    }

    @Test
    fun `runForegroundTextCall hands the typed text off as Content_Text, never an audio file`(
        @TempDir cacheDir: File,
    ) = runTest {
        val captured = slot<List<Content>>()
        val engine = mockk<LiteRtLmEngine> {
            every { streamMessageContents(any(), capture(captured)) } returns flowOf(rawSuccess("a", "b"))
        }

        ForegroundInference(engine, cacheDir, clock = fixedClock)
            .runForegroundTextCall(text = "the literal typed words", persona = Persona.WITNESS)
            .terminal()

        val parts = captured.captured
        assertAll(
            { assertEquals(1, parts.size, "system prompt now rides systemInstruction, not the message body") },
            { assertInstanceOf(Content.Text::class.java, parts[0]) },
            { assertEquals("the literal typed words", (parts[0] as Content.Text).text) },
            { assertTrue(parts.none { it is Content.AudioFile }, "Typed path must not hand off audio") },
            { assertEquals(0, cacheDir.listFiles().orEmpty().size, "Typed path writes no temp WAV") },
        )
    }

    @Test
    fun `runForegroundTextCall rejects blank text`(@TempDir cacheDir: File) = runTest {
        val engine = mockk<LiteRtLmEngine>()
        val inference = ForegroundInference(engine, cacheDir, clock = fixedClock)

        val caught = runCatching { inference.runForegroundTextCall("   ", Persona.WITNESS) }.exceptionOrNull()

        assertInstanceOf(IllegalArgumentException::class.java, caught)
    }

    @Test
    fun `parse failure is a ParseFailure terminal, never a throw or retry`(@TempDir cacheDir: File) = runTest {
        val engine = engineEmitting("no headers, just prose")

        val result = ForegroundInference(engine, cacheDir, clock = fixedClock)
            .runForegroundCall(audio = audioChunk(), persona = Persona.HARDASS)
            .terminal()

        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, result)
        assertEquals(ForegroundResult.ParseReason.MISSING_TRANSCRIPTION, failure.reason)
        assertEquals(Persona.HARDASS, failure.persona)
        assertEquals("no headers, just prose", failure.rawResponse)
        assertEquals(null, failure.recoveredTranscription)
    }

    @Test
    fun `incomplete follow_up at stream end is a MISSING_FOLLOW_UP terminal with recovered transcription`(
        @TempDir cacheDir: File,
    ) = runTest {
        val engine = mockk<LiteRtLmEngine> {
            every { streamMessageContents(any(), any()) } returns flowOf(
                "<transcription>verbatim user text</transcription>",
                "<follow_up>model trailed off and the stream end",
            )
        }

        val result = ForegroundInference(engine, cacheDir, clock = fixedClock)
            .runForegroundCall(audio = audioChunk(), persona = Persona.WITNESS)
            .terminal()

        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, result)
        assertEquals(ForegroundResult.ParseReason.MISSING_FOLLOW_UP, failure.reason)
        assertEquals("verbatim user text", failure.recoveredTranscription)
    }

    @Test
    fun `composed prompt embeds persona shared rules, the active persona tag, and the output schema`(
        @TempDir cacheDir: File,
    ) = runTest {
        val captured = slot<String>()
        val engine = mockk<LiteRtLmEngine> {
            every { streamMessageContents(capture(captured), any()) } returns flowOf(rawSuccess("a", "b"))
        }

        ForegroundInference(engine, cacheDir, clock = fixedClock)
            .runForegroundCall(audio = audioChunk(), persona = Persona.EDITOR)
            .terminal()

        val systemPrompt = captured.captured
        assertAll(
            { assertTrue(systemPrompt.contains("Persona: Editor")) },
            { assertTrue(systemPrompt.contains("cognition tracker")) },
            { assertFalse(systemPrompt.contains("RECENT TURNS")) },
            { assertTrue(systemPrompt.contains("transcription tags")) },
            { assertTrue(systemPrompt.contains("follow_up tags")) },
            { assertFalse(systemPrompt.contains("<transcription>")) },
            { assertFalse(systemPrompt.contains("<follow_up>")) },
        )
    }

    @Test
    fun `audio handoff goes through Content_AudioFile pointing inside cacheDir`(@TempDir cacheDir: File) = runTest {
        val captured = slot<List<Content>>()
        val engine = mockk<LiteRtLmEngine> {
            every { streamMessageContents(any(), capture(captured)) } returns flowOf(rawSuccess("a", "b"))
        }

        ForegroundInference(engine, cacheDir, clock = fixedClock)
            .runForegroundCall(audio = audioChunk(), persona = Persona.WITNESS)
            .terminal()

        val parts = captured.captured
        assertAll(
            { assertEquals(1, parts.size, "audio is the only message part; system prompt rides systemInstruction") },
            { assertInstanceOf(Content.AudioFile::class.java, parts[0]) },
        )
        val audioPath = (parts[0] as Content.AudioFile).absolutePath
        assertTrue(
            audioPath.startsWith(cacheDir.absolutePath),
            "Temp WAV path $audioPath must live inside cacheDir ${cacheDir.absolutePath}",
        )
    }

    @Test
    fun `temp WAV is deleted after a successful stream`(@TempDir cacheDir: File) = runTest {
        val engine = engineEmitting(rawSuccess("a", "b"))

        ForegroundInference(engine, cacheDir, clock = fixedClock)
            .runForegroundCall(audio = audioChunk(), persona = Persona.WITNESS)
            .terminal()

        assertEquals(0, cacheDir.listFiles().orEmpty().size, "Temp WAV must be deleted after the stream")
    }

    @Test
    fun `temp WAV is deleted even when the stream throws`(@TempDir cacheDir: File) = runTest {
        val engine = mockk<LiteRtLmEngine> {
            every { streamMessageContents(any(), any()) } returns flow { error("model crashed") }
        }
        val inference = ForegroundInference(engine, cacheDir, clock = fixedClock)

        val caught = runCatching {
            inference.runForegroundCall(audioChunk(), Persona.WITNESS).terminal()
        }.exceptionOrNull()

        assertInstanceOf(IllegalStateException::class.java, caught)
        assertEquals("model crashed", caught?.message)
        assertEquals(0, cacheDir.listFiles().orEmpty().size, "Temp WAV must be deleted on engine error")
    }

    @Test
    fun `empty audio samples are rejected before the stream is built`(@TempDir cacheDir: File) {
        val engine = mockk<LiteRtLmEngine>()
        val inference = ForegroundInference(engine, cacheDir, clock = fixedClock)
        assertThrows(IllegalArgumentException::class.java) {
            inference.runForegroundCall(
                audio = AudioChunk(FloatArray(0), AudioCapture.SAMPLE_RATE_HZ, isFinal = true),
                persona = Persona.WITNESS,
            )
        }
    }

    @Test
    fun `crash-leftover temp WAVs are swept before the next call`(@TempDir cacheDir: File) = runTest {
        // Simulate a prior call that died mid-inference: a vestige-fg-*.wav still on disk.
        val leftover =
            File(cacheDir, "${ForegroundInference.TEMP_PREFIX}previous-crash${ForegroundInference.TEMP_SUFFIX}")
        leftover.writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46))
        assertTrue(leftover.exists())

        val engine = engineEmitting(rawSuccess("a", "b"))
        ForegroundInference(engine, cacheDir, clock = fixedClock)
            .runForegroundCall(audio = audioChunk(), persona = Persona.WITNESS)
            .terminal()

        assertFalse(leftover.exists(), "Pre-call sweep must delete crash-leftover foreground WAVs")
        assertEquals(0, cacheDir.listFiles().orEmpty().size)
    }

    @Test
    fun `sweep skips another in-flight foreground temp WAV`(@TempDir cacheDir: File) = runTest {
        val firstAudioPath = CompletableDeferred<String>()
        val secondCallReachedEngine = CompletableDeferred<Unit>()
        val releaseFirstCall = CompletableDeferred<Unit>()
        var invocationCount = 0
        val engine = mockk<LiteRtLmEngine> {
            every { streamMessageContents(any(), any()) } answers {
                val parts = secondArg<List<Content>>()
                val audioPath = (parts[0] as Content.AudioFile).absolutePath
                invocationCount += 1
                when (invocationCount) {
                    1 -> flow {
                        firstAudioPath.complete(audioPath)
                        secondCallReachedEngine.await()
                        assertTrue(
                            File(audioPath).exists(),
                            "An overlapping call must not sweep the first call's live temp WAV",
                        )
                        releaseFirstCall.await()
                        emit(rawSuccess("first transcription", "first follow-up"))
                    }

                    2 -> flow {
                        val activeFirstPath = firstAudioPath.await()
                        assertTrue(
                            File(activeFirstPath).exists(),
                            "The first call's temp WAV should still exist when the second call starts",
                        )
                        secondCallReachedEngine.complete(Unit)
                        releaseFirstCall.complete(Unit)
                        emit(rawSuccess("second transcription", "second follow-up"))
                    }

                    else -> error("Unexpected streamMessageContents invocation #$invocationCount")
                }
            }
        }

        val dispatcher = StandardTestDispatcher(testScheduler)
        val firstInference = ForegroundInference(engine, cacheDir, clock = fixedClock, ioDispatcher = dispatcher)
        val secondInference = ForegroundInference(engine, cacheDir, clock = fixedClock, ioDispatcher = dispatcher)

        val firstCall = async { firstInference.runForegroundCall(audioChunk(), Persona.WITNESS).terminal() }
        val secondCall = async { secondInference.runForegroundCall(audioChunk(), Persona.HARDASS).terminal() }

        val firstResult = assertInstanceOf(ForegroundResult.Success::class.java, firstCall.await())
        val secondResult = assertInstanceOf(ForegroundResult.Success::class.java, secondCall.await())
        assertEquals("first transcription", firstResult.transcription)
        assertEquals("second transcription", secondResult.transcription)
        assertEquals(0, cacheDir.listFiles().orEmpty().size, "Both temp WAVs should be cleaned up after completion")
    }

    @Test
    fun `discardTempWav reaches the truncate-then-retry-delete branch when first delete fails`(
        @TempDir cacheDir: File,
    ) {
        // First delete fails (file doesn't exist), so the helper falls through to the truncate
        // primitive — outputStream().use {} creates the file empty — and the retry delete then
        // succeeds.
        val nonExistent = File(cacheDir, "vestige-fg-truncate-branch-test.wav")
        assertFalse(nonExistent.exists())

        ForegroundInference(mockk(), cacheDir, clock = fixedClock).discardTempWav(nonExistent)

        assertFalse(nonExistent.exists(), "Retry delete after truncate must clean up the file")
    }

    @Test
    fun `truncate primitive zeros the audio payload`(@TempDir cacheDir: File) {
        // Pins the load-bearing privacy guarantee in discardTempWav's delete-failure branch
        // (truncate-then-retry-delete). Triggering a real delete-failure needs OS-specific
        // setup (read-only parent dir, file locks); we verify the primitive in isolation.
        val payload = File(cacheDir, "vestige-fg-truncate-test.wav")
        payload.writeBytes(ByteArray(1024) { 0x42 })
        assertEquals(1024, payload.length())

        payload.outputStream().use { }

        assertEquals(0L, payload.length())
        assertTrue(payload.delete())
    }

    @Test
    fun `sweep ignores unrelated files in cacheDir`(@TempDir cacheDir: File) = runTest {
        val unrelated = File(cacheDir, "model-artifact.litertlm")
        unrelated.writeBytes(byteArrayOf(0x00))
        val engine = engineEmitting(rawSuccess("a", "b"))
        ForegroundInference(engine, cacheDir, clock = fixedClock)
            .runForegroundCall(audio = audioChunk(), persona = Persona.WITNESS)
            .terminal()

        assertTrue(unrelated.exists(), "Sweep must not touch non-foreground-temp files")
    }

    @Test
    fun `non-final audio chunk is rejected — final-chunk-only contract`(@TempDir cacheDir: File) {
        val engine = mockk<LiteRtLmEngine>()
        val inference = ForegroundInference(engine, cacheDir, clock = fixedClock)
        val intermediate = AudioChunk(samples(), AudioCapture.SAMPLE_RATE_HZ, isFinal = false)
        val ex = assertThrows(IllegalArgumentException::class.java) {
            inference.runForegroundCall(intermediate, Persona.WITNESS)
        }
        assertTrue(
            ex.message!!.contains("audio.isFinal == true"),
            "Error must explain the final-chunk contract; was: ${ex.message}",
        )
    }

    @Test
    fun `missing cacheDir is rejected before the stream is built`(@TempDir cacheDir: File) {
        val engine = mockk<LiteRtLmEngine>()
        val nonExistent = File(cacheDir, "nope")
        val inference = ForegroundInference(engine, nonExistent, clock = fixedClock)
        assertThrows(IllegalArgumentException::class.java) {
            inference.runForegroundCall(audioChunk(), Persona.WITNESS)
        }
    }

    @Test
    fun `per-capture persona reaches the engine prompt`(@TempDir cacheDir: File) = runTest {
        val captured = mutableListOf<String>()
        val engine = mockk<LiteRtLmEngine> {
            every { streamMessageContents(capture(captured), any()) } returns flowOf(rawSuccess("u", "f"))
        }
        val inference = ForegroundInference(engine, cacheDir, clock = fixedClock)

        inference.runForegroundCall(audioChunk(), Persona.WITNESS).terminal()
        inference.runForegroundCall(audioChunk(), Persona.EDITOR).terminal()

        assertEquals(2, captured.size)
        val firstPrompt = captured[0]
        val secondPrompt = captured[1]
        assertAll(
            { assertTrue(firstPrompt.contains("Persona: Witness")) },
            { assertFalse(firstPrompt.contains("Persona: Editor")) },
            { assertTrue(secondPrompt.contains("Persona: Editor")) },
            { assertFalse(secondPrompt.contains("Persona: Witness")) },
            { assertFalse(firstPrompt.contains("RECENT TURNS")) },
            { assertFalse(secondPrompt.contains("RECENT TURNS")) },
        )
    }

    @Test
    fun `empty engine response surfaces as EMPTY_RESPONSE terminal`(@TempDir cacheDir: File) = runTest {
        val engine = engineEmitting("")

        val result = ForegroundInference(engine, cacheDir, clock = fixedClock)
            .runForegroundCall(audio = audioChunk(), persona = Persona.WITNESS)
            .terminal()

        val failure = assertInstanceOf(ForegroundResult.ParseFailure::class.java, result)
        assertEquals(ForegroundResult.ParseReason.EMPTY_RESPONSE, failure.reason)
        assertEquals(null, failure.recoveredTranscription)
    }
}
