package dev.anchildress1.vestige

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.vestige.inference.AudioCapture
import dev.anchildress1.vestige.inference.BackendChoice
import dev.anchildress1.vestige.inference.LiteRtLmEngine
import dev.anchildress1.vestige.inference.SttAProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Story 1.5 — STT-A existential audio plumbing test.
 *
 * Probes both `Content.AudioBytes` (raw float32-LE) and `Content.AudioFile` (PCM_FLOAT WAV)
 * handoffs against Gemma 4 E4B on the reference device. The human running this test fills in
 * ADR-001 §Q4 with which path actually produces a coherent transcription and the round-trip
 * latency for one 30-second clip.
 *
 * Prerequisites — adb-push the model and a sample WAV (16 kHz mono PCM_FLOAT, ≤ 30 s) to the
 * device's `Android/data/.../files` directory:
 *
 *   adb push gemma-4-E4B-it.litertlm <BASE>/models/
 *   adb push sample.wav <BASE>/audio/
 *
 * where `<BASE>` is `/sdcard/Android/data/dev.anchildress1.vestige/files`. Then run:
 *
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.modelPath=<BASE>/models/gemma-4-E4B-it.litertlm \
 *     -Pandroid.testInstrumentationRunnerArguments.audioPath=<BASE>/audio/sample.wav
 *
 * If either argument is missing the test is skipped via [assumeTrue] so CI without the
 * artifacts stays green.
 */
@RunWith(AndroidJUnit4::class)
class SttAAudioPlumbingTest {

    @Test
    fun gemma4E4B_audioBytes_audioFile_roundTrip() = runBlocking {
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

        val engine = LiteRtLmEngine(
            modelPath = modelPath,
            backend = BackendChoice.Cpu,
            audioBackend = BackendChoice.Cpu,
            cacheDir = context.cacheDir.absolutePath,
        )

        engine.use {
            it.initialize()
            val probe = SttAProbe(it)

            val samples = readMonoFloatWavSamples(audioFile)
            val sampleRateHz = AudioCapture.SAMPLE_RATE_HZ

            val audioFileResponse = probe.transcribeAudioFile(audioFile.absolutePath)
            assertTrue("AudioFile path returned blank", audioFileResponse.isNotBlank())

            val audioBytesResponse = probe.transcribeAudioBytesAsFloat32Le(samples)
            assertTrue("AudioBytes(float32-LE) path returned blank", audioBytesResponse.isNotBlank())

            val tempWavResponse = probe.transcribeViaTempWav(
                samples = samples,
                sampleRateHz = sampleRateHz,
                cacheDir = context.cacheDir,
            )
            assertTrue("Temp-WAV path returned blank", tempWavResponse.isNotBlank())
        }
    }

    /**
     * Read raw PCM_FLOAT samples from a mono WAV file. Stripped down — assumes the canonical
     * 44-byte RIFF/WAVE/fmt/data layout produced by [dev.anchildress1.vestige.inference.WavWriter].
     */
    private fun readMonoFloatWavSamples(file: File): FloatArray {
        val raw = file.readBytes()
        check(raw.size > WAV_HEADER_BYTES) { "WAV file too small: ${file.absolutePath}" }
        val payload = raw.copyOfRange(WAV_HEADER_BYTES, raw.size)
        val out = FloatArray(payload.size / BYTES_PER_FLOAT)
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }

    private companion object {
        const val WAV_HEADER_BYTES = 44
        const val BYTES_PER_FLOAT = 4
    }
}
