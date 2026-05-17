package dev.anchildress1.vestige

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.vestige.inference.AudioCapture
import dev.anchildress1.vestige.inference.AudioChunk
import dev.anchildress1.vestige.inference.BackendChoice
import dev.anchildress1.vestige.inference.ForegroundInference
import dev.anchildress1.vestige.inference.ForegroundResult
import dev.anchildress1.vestige.inference.ForegroundStreamEvent
import dev.anchildress1.vestige.inference.LiteRtLmEngine
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
 * Per-capture persona smoke test (manual, on-device, visual inspection).
 *
 * Drives the foreground path three times — once per persona — against the same audio buffer.
 * Asserts each follow-up is non-blank, pairwise-different, and the [ForegroundResult.Success]
 * carries the capture's persona. Operator inspects logcat for tone.
 *
 * Push artifacts then run:
 *
 *   adb push gemma-4-E4B-it.litertlm /data/local/tmp/
 *   adb push sample.wav              /data/local/tmp/
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -PmodelPath=/data/local/tmp/gemma-4-E4B-it.litertlm \
 *     -PaudioPath=/data/local/tmp/sample.wav \
 *     -Pandroid.testInstrumentationRunnerArguments.class=dev.anchildress1.vestige.PerCapturePersonaSmokeTest
 *
 * Missing args → [assumeTrue] skips so CI without artifacts stays green.
 */
@RunWith(AndroidJUnit4::class)
class PerCapturePersonaSmokeTest {

    @Test
    fun threePersonas_eachInOwnSession_produceDivergentFollowUps() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val modelPath = args.getString("modelPath")
        val audioPath = args.getString("audioPath")
        val latencyBudgetMs = args.getString("latencyBudgetMs")?.toLongOrNull() ?: DEFAULT_LATENCY_BUDGET_MS
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

            val captures = Persona.entries.associateWith { persona ->
                runOneCaptureUnderPersona(inference, chunk, persona)
            }

            assertPairwiseDifferent(captures.mapValues { (_, capture) -> capture.followUp })
            assertWithinLatencyBudget(captures, latencyBudgetMs)
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

    /**
     * Drive one foreground call under [persona], folding the stream to its terminal result.
     * Returns the follow-up text + measured per-call latency for the caller's divergence +
     * budget assertions.
     */
    private suspend fun runOneCaptureUnderPersona(
        inference: ForegroundInference,
        chunk: AudioChunk,
        persona: Persona,
    ): CaptureResult {
        var terminal: ForegroundResult? = null
        inference.runForegroundCall(chunk, persona).collect { event ->
            if (event is ForegroundStreamEvent.Terminal) terminal = event.result
        }
        val result = checkNotNull(terminal) { "${persona.name} capture produced no Terminal event" }
        assertTrue("${persona.name} capture must succeed; was $result", result is ForegroundResult.Success)
        val success = result as ForegroundResult.Success
        assertEquals("Result must carry the capture's persona", persona, success.persona)

        android.util.Log.i(TAG, "=== ${persona.name} follow-up (${success.elapsedMs}ms) ===")
        android.util.Log.i(TAG, success.followUp)
        return CaptureResult(followUp = success.followUp, elapsedMs = success.elapsedMs)
    }

    /**
     * Default budget (60_000 ms) catches a ~2× regression past the documented ~24–33 s E4B CPU
     * baseline without failing on the unmet 1–5 s ADR-002 target. Override with
     * `-PlatencyBudgetMs=<ms>`.
     */
    private fun assertWithinLatencyBudget(captures: Map<Persona, CaptureResult>, latencyBudgetMs: Long) {
        val overruns = captures.filterValues { it.elapsedMs > latencyBudgetMs }
        if (overruns.isNotEmpty()) {
            val detail = overruns.entries.joinToString(", ") { (p, c) -> "${p.name}=${c.elapsedMs}ms" }
            error(
                "Per-capture latency exceeded ${latencyBudgetMs}ms: $detail. " +
                    "Re-run on a quiescent device before treating as a hard fail.",
            )
        }
    }

    private data class CaptureResult(val followUp: String, val elapsedMs: Long)

    /** Mono WAV reader (PCM_S16LE or IEEE_FLOAT). Inlined; mirrors `SttAAudioPlumbingTest`. */
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
        const val TAG = "VestigePerCapturePersona"
        const val DEFAULT_LATENCY_BUDGET_MS = 60_000L
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
