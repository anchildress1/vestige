package dev.anchildress1.vestige

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.vestige.inference.AudioCapture
import dev.anchildress1.vestige.inference.AudioChunk
import dev.anchildress1.vestige.inference.BackendChoice
import dev.anchildress1.vestige.inference.CaptureSession
import dev.anchildress1.vestige.inference.ForegroundInference
import dev.anchildress1.vestige.inference.ForegroundResult
import dev.anchildress1.vestige.inference.LiteRtLmEngine
import dev.anchildress1.vestige.inference.Speaker
import dev.anchildress1.vestige.model.Persona
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Story 2.3 — persona-switching smoke test (manual, visual inspection).
 *
 * Drives the full Story 2.2 foreground path three times inside a single [CaptureSession], swapping
 * persona between turns via [CaptureSession.setPersona] — Witness → Hardass → Editor — and
 * verifies that:
 *  - each turn's follow-up is non-blank and pairwise-different from the others;
 *  - prior model turns retain their authoring persona (Story 2.3 done-when 2);
 *  - the same audio buffer fed through all three personas produces visibly different follow-ups
 *    on logcat tag `[VestigePersonaSwitch]` (Story 2.3 done-when 5).
 *
 * The follow-ups are stochastic; the assertion is "different strings", not a fixed shape. The
 * human running this test compares the two logcat blocks visually.
 *
 * Prerequisites — adb-push the model and a sample WAV (16 kHz mono PCM_S16LE, ≤ 30 s) to the
 * device's `Android/data/.../files` directory:
 *
 *   adb push gemma-4-E4B-it.litertlm <BASE>/models/
 *   adb push sample.wav <BASE>/audio/
 *
 * Then run:
 *
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.modelPath=<BASE>/models/gemma-4-E4B-it.litertlm \
 *     -Pandroid.testInstrumentationRunnerArguments.audioPath=<BASE>/audio/sample.wav
 *
 * If either argument is missing the test is skipped via [assumeTrue] so CI without the
 * artifacts stays green.
 */
@RunWith(AndroidJUnit4::class)
class PersonaSwitchSessionSmokeTest {

    @Test
    fun setPersona_acrossThreeTurns_producesDivergentFollowUps() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val modelPath = args.getString("modelPath")
        val audioPath = args.getString("audioPath")
        assumeTrue("modelPath instrumentation argument not provided", modelPath != null)
        assumeTrue("audioPath instrumentation argument not provided", audioPath != null)
        val modelFile = File(modelPath!!)
        val audioFile = File(audioPath!!)
        assumeTrue("Model file not found at $modelPath", modelFile.exists() && modelFile.canRead())
        assumeTrue("Audio file not found at $audioPath", audioFile.exists() && audioFile.canRead())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cacheDir = context.cacheDir
        val chunk = AudioChunk(
            samples = readMonoFloatWavSamples(audioFile),
            sampleRateHz = AudioCapture.SAMPLE_RATE_HZ,
            isFinal = true,
        )

        val engine = LiteRtLmEngine(
            modelPath = modelPath,
            backend = BackendChoice.Cpu,
            audioBackend = BackendChoice.Cpu,
            cacheDir = cacheDir.absolutePath,
        )

        engine.use {
            it.initialize()
            val inference = ForegroundInference(it, cacheDir)
            val session = CaptureSession()

            val followUps = Persona.entries.associateWith { persona ->
                if (session.activePersona != persona) session.setPersona(persona)
                assertEquals(persona, session.activePersona)
                runOneTurn(inference, session, chunk, persona.name)
            }

            assertPairwiseDifferent(followUps)
            assertHistoryPreservesAuthoringPersona(session, expectedOrder = Persona.entries)
        }
    }

    private fun assertPairwiseDifferent(followUps: Map<Persona, String>) {
        followUps.forEach { (persona, text) ->
            assertTrue("${persona.name} follow-up must be non-blank", text.isNotBlank())
        }
        val personas = followUps.keys.toList()
        for (i in personas.indices) {
            for (j in i + 1 until personas.size) {
                assertNotEquals(
                    "${personas[i].name} vs ${personas[j].name} — same audio must produce different follow-up text",
                    followUps.getValue(personas[i]).trim(),
                    followUps.getValue(personas[j]).trim(),
                )
            }
        }
    }

    private fun assertHistoryPreservesAuthoringPersona(session: CaptureSession, expectedOrder: List<Persona>) {
        val modelTurns = session.transcript.turns.filter { turn -> turn.speaker == Speaker.MODEL }
        assertEquals(expectedOrder.size, modelTurns.size)
        expectedOrder.forEachIndexed { index, persona ->
            assertEquals(
                "Turn $index model persona must remain ${persona.name} — setPersona does not rewrite history",
                persona,
                modelTurns[index].persona,
            )
        }
    }

    /**
     * Drives one turn through the Story 2.2 foreground path against [session]'s active persona,
     * advances the state machine through TRANSCRIBED → RESPONDED → IDLE, logs the follow-up
     * under [label], and returns the follow-up text for the caller to compare across turns.
     */
    private suspend fun runOneTurn(
        inference: ForegroundInference,
        session: CaptureSession,
        chunk: AudioChunk,
        label: String,
    ): String {
        session.startRecording()
        session.submitForInference()
        val result = inference.runForegroundCall(chunk, session.transcript, session.activePersona)
        assertTrue("$label turn must succeed; was $result", result is ForegroundResult.Success)
        val success = result as ForegroundResult.Success
        session.recordTranscription(success.transcription)
        session.recordModelResponse(success.followUp, success.persona)
        session.acknowledgeResponse()
        android.util.Log.i(TAG, "=== $label follow-up ===")
        android.util.Log.i(TAG, success.followUp)
        return success.followUp
    }

    /**
     * Reads a mono WAV (PCM_S16LE or IEEE_FLOAT) and returns normalized float samples. Mirrors
     * the parser in `SttAAudioPlumbingTest` — kept inline so this smoke test stays a single file.
     */
    private fun readMonoFloatWavSamples(file: File): FloatArray {
        val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(RIFF_WAVE_PREFIX_BYTES)

        var audioFormat = 0
        var bitsPerSample = 0

        while (buf.remaining() >= CHUNK_HEADER_BYTES) {
            val id = ByteArray(CHUNK_ID_BYTES).also { buf.get(it) }.toString(Charsets.US_ASCII)
            val chunkSize = buf.int
            when (id) {
                "fmt " -> {
                    audioFormat = buf.short.toInt() and 0xFFFF
                    buf.short
                    buf.int
                    buf.int
                    buf.short
                    bitsPerSample = buf.short.toInt() and 0xFFFF
                    val consumed = 16
                    if (chunkSize > consumed) buf.position(buf.position() + chunkSize - consumed)
                }

                "data" -> {
                    return when {
                        audioFormat == FMT_IEEE_FLOAT && bitsPerSample == 32 -> {
                            FloatArray(chunkSize / BYTES_PER_FLOAT).also { out ->
                                buf.asFloatBuffer().get(out)
                            }
                        }

                        audioFormat == FMT_PCM && bitsPerSample == 16 -> {
                            val sampleCount = chunkSize / BYTES_PER_INT16
                            FloatArray(sampleCount) { buf.short / PCM16_SCALE }
                        }

                        else -> error("Unsupported WAV format=$audioFormat bits=$bitsPerSample in ${file.name}")
                    }
                }

                else -> buf.position(buf.position() + chunkSize + (chunkSize and 1))
            }
        }
        error("No 'data' chunk found in WAV file: ${file.absolutePath}")
    }

    private companion object {
        const val TAG = "VestigePersonaSwitch"
        const val RIFF_WAVE_PREFIX_BYTES = 12
        const val CHUNK_HEADER_BYTES = 8
        const val CHUNK_ID_BYTES = 4
        const val BYTES_PER_FLOAT = 4
        const val BYTES_PER_INT16 = 2
        const val FMT_PCM = 1
        const val FMT_IEEE_FLOAT = 3
        const val PCM16_SCALE = 32768f
    }
}
