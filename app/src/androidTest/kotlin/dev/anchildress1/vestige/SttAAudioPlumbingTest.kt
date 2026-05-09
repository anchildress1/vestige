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

            // Each path is tried independently — the probe's job is to discover which one
            // produces a coherent transcription. All three are logged; at least one must succeed.
            val audioFileResult = runCatching {
                probe.transcribeAudioFile(audioFile.absolutePath)
            }
            val audioBytesResult = runCatching {
                probe.transcribeAudioBytesAsFloat32Le(samples)
            }
            val tempWavResult = runCatching {
                probe.transcribeViaTempWav(samples = samples, sampleRateHz = sampleRateHz, cacheDir = context.cacheDir)
            }

            android.util.Log.i(TAG, "AudioFile  : ${audioFileResult.summarize()}")
            android.util.Log.i(TAG, "AudioBytes : ${audioBytesResult.summarize()}")
            android.util.Log.i(TAG, "TempWAV    : ${tempWavResult.summarize()}")

            val anySuccess = listOf(audioFileResult, audioBytesResult, tempWavResult)
                .any { result -> result.getOrNull()?.isNotBlank() == true }
            assertTrue(
                "All three STT-A paths failed — see logcat tag $TAG for details",
                anySuccess,
            )
        }
    }

    /**
     * Read normalized float32 samples from a mono WAV file. Handles both PCM_S16LE (format 1)
     * and IEEE_FLOAT (format 3). Scans chunk headers rather than assuming a fixed offset — WAV
     * files from real encoders include `fact`, `LIST`, and other chunks before `data`.
     */
    private fun readMonoFloatWavSamples(file: File): FloatArray {
        val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(RIFF_WAVE_PREFIX_BYTES) // skip "RIFF" + file-size + "WAVE"

        var audioFormat = 0
        var bitsPerSample = 0

        while (buf.remaining() >= CHUNK_HEADER_BYTES) {
            val id = ByteArray(CHUNK_ID_BYTES).also { buf.get(it) }.toString(Charsets.US_ASCII)
            val chunkSize = buf.int
            when (id) {
                "fmt " -> {
                    audioFormat = buf.short.toInt() and 0xFFFF
                    buf.short // channels
                    buf.int // sampleRate
                    buf.int // byteRate
                    buf.short // blockAlign
                    bitsPerSample = buf.short.toInt() and 0xFFFF
                    // Skip any fmt extension bytes
                    val consumed = 16
                    if (chunkSize > consumed) buf.position(buf.position() + chunkSize - consumed)
                }

                "data" -> {
                    return when {
                        audioFormat == FMT_IEEE_FLOAT && bitsPerSample == 32 -> {
                            FloatArray(chunkSize / BYTES_PER_FLOAT).also {
                                buf.asFloatBuffer().get(it)
                            }
                        }

                        audioFormat == FMT_PCM && bitsPerSample == 16 -> {
                            val sampleCount = chunkSize / BYTES_PER_INT16
                            FloatArray(sampleCount) {
                                buf.short / PCM16_SCALE
                            }
                        }

                        else -> error(
                            "Unsupported WAV format=$audioFormat bits=$bitsPerSample in ${file.name}",
                        )
                    }
                }

                else -> buf.position(buf.position() + chunkSize + (chunkSize and 1))
            }
        }
        error("No 'data' chunk found in WAV file: ${file.absolutePath}")
    }

    private fun Result<String>.summarize(): String = fold(
        onSuccess = { if (it.isBlank()) "BLANK response" else "OK — \"${it.take(120)}\"" },
        onFailure = { "FAILED — ${it.javaClass.simpleName}: ${it.message?.take(120)}" },
    )

    private companion object {
        const val TAG = "VestigeSttA"
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
