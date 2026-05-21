package dev.anchildress1.vestige

import android.app.Instrumentation
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.vestige.inference.AudioBackendChoice
import dev.anchildress1.vestige.inference.AudioCapture
import dev.anchildress1.vestige.inference.AudioChunk
import dev.anchildress1.vestige.inference.BackendChoice
import dev.anchildress1.vestige.inference.ForegroundInference
import dev.anchildress1.vestige.inference.ForegroundResult
import dev.anchildress1.vestige.inference.ForegroundStreamEvent
import dev.anchildress1.vestige.inference.LiteRtLmEngine
import dev.anchildress1.vestige.model.Persona
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Device smoke for perceived post-stop spinner time.
 *
 * Runs the same audio twice:
 * - no pre-warm: timer starts before the engine wrapper is built and initialized.
 * - pre-warmed: timer starts after the engine is already initialized.
 *
 * Push artifacts then run:
 *
 *   adb push gemma-4-E4B-it.litertlm /data/local/tmp/
 *   adb push sample.wav              /data/local/tmp/
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -PmodelPath=/data/local/tmp/gemma-4-E4B-it.litertlm \
 *     -PaudioPath=/data/local/tmp/sample.wav \
 *     -Pandroid.testInstrumentationRunnerArguments.class=dev.anchildress1.vestige.ForegroundSpinnerTimingSmokeTest
 */
@RunWith(AndroidJUnit4::class)
class ForegroundSpinnerTimingSmokeTest {

    @Test
    fun sameAudio_reportsNoPrewarmVsPrewarmedSpinnerTime() = runBlocking {
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

        val coldStarted = System.nanoTime()
        val engine = LiteRtLmEngine(
            modelPath = modelPath,
            backend = BackendChoice.Gpu,
            audioBackend = AudioBackendChoice.Cpu,
            cacheDir = cacheDir.absolutePath,
        )
        engine.use {
            it.initialize()
            val inference = ForegroundInference(it, cacheDir)
            val cold = runTimedCapture(
                startedAtNanos = coldStarted,
                label = "no_prewarm",
                inference = inference,
                chunk = chunk,
            )
            val warm = runTimedCapture(
                startedAtNanos = System.nanoTime(),
                label = "prewarmed",
                inference = inference,
                chunk = chunk,
            )
            val deltaMs = cold.spinnerMs - warm.spinnerMs
            val summary = "perceived_spinner cold_no_prewarm_ms=${cold.spinnerMs} " +
                "warm_prewarmed_ms=${warm.spinnerMs} delta_ms=$deltaMs " +
                "cold_model_ms=${cold.modelElapsedMs} warm_model_ms=${warm.modelElapsedMs}"
            android.util.Log.i(TAG, summary)
            reportInstrumentationResult(summary)

            assertTrue("cold spinner time must be positive", cold.spinnerMs > 0L)
            assertTrue("prewarmed spinner time must be positive", warm.spinnerMs > 0L)
            assertTrue("cold transcription must be non-blank", cold.transcription.isNotBlank())
            assertTrue("prewarmed transcription must be non-blank", warm.transcription.isNotBlank())
        }
    }

    private fun reportInstrumentationResult(summary: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\n$summary\n")
            },
        )
    }

    private suspend fun runTimedCapture(
        startedAtNanos: Long,
        label: String,
        inference: ForegroundInference,
        chunk: AudioChunk,
    ): SpinnerMeasurement {
        var terminal: ForegroundResult? = null
        inference.runForegroundCall(chunk, Persona.WITNESS).collect { event ->
            if (event is ForegroundStreamEvent.Terminal) terminal = event.result
        }
        val spinnerMs = (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLI
        val result = checkNotNull(terminal) { "$label capture produced no Terminal event" }
        assertTrue("$label capture must succeed; was $result", result is ForegroundResult.Success)
        val success = result as ForegroundResult.Success
        android.util.Log.i(
            TAG,
            "$label spinner_ms=$spinnerMs model_elapsed_ms=${success.elapsedMs} " +
                "transcription_len=${success.transcription.length} follow_up_len=${success.followUp.length}",
        )
        return SpinnerMeasurement(
            spinnerMs = spinnerMs,
            modelElapsedMs = success.elapsedMs,
            transcription = success.transcription,
        )
    }

    private data class SpinnerMeasurement(val spinnerMs: Long, val modelElapsedMs: Long, val transcription: String)

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
                    audioFormat = buf.short.toInt() and UINT16_MASK
                    buf.short
                    buf.int
                    buf.int
                    buf.short
                    bitsPerSample = buf.short.toInt() and UINT16_MASK
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
        const val TAG = "VestigeSpinnerSmoke"
        const val NANOS_PER_MILLI = 1_000_000L
        const val RIFF_WAVE_PREFIX_BYTES = 12
        const val CHUNK_HEADER_BYTES = 8
        const val CHUNK_ID_BYTES = 4
        const val UINT16_MASK = 0xFFFF
        const val BYTES_PER_FLOAT = 4
        const val BYTES_PER_INT16 = 2
        const val FMT_PCM = 1
        const val FMT_IEEE_FLOAT = 3
        const val PCM16_SCALE = 32768f
    }
}
