package dev.anchildress1.vestige

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.vestige.inference.AudioCapture
import dev.anchildress1.vestige.inference.AudioChunk
import dev.anchildress1.vestige.inference.BackendChoice
import dev.anchildress1.vestige.inference.ForegroundInference
import dev.anchildress1.vestige.inference.ForegroundResult
import dev.anchildress1.vestige.inference.LiteRtLmEngine
import dev.anchildress1.vestige.model.Persona
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Goblin-hours addendum smoke test (manual, on-device, visual inspection).
 *
 * Drives the foreground path twice against identical audio + persona — once with a clock fixed at
 * 03:00 local (inside the goblin window) and once at 12:00 local (outside). Operator inspects
 * logcat to confirm the 03:00 follow-up is materially shorter / less probing and that neither
 * follow-up names the hour. Asserts pairwise-different and non-blank so an inert addendum (model
 * ignored the instruction) shows up as a hard fail.
 *
 *   adb push gemma-4-E4B-it.litertlm /data/local/tmp/
 *   adb push sample.wav              /data/local/tmp/
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -PmodelPath=/data/local/tmp/gemma-4-E4B-it.litertlm \
 *     -PaudioPath=/data/local/tmp/sample.wav \
 *     -Pandroid.testInstrumentationRunnerArguments.class=dev.anchildress1.vestige.GoblinHoursAddendumSmokeTest
 *
 * Missing args → [assumeTrue] skips so CI without artifacts stays green.
 */
@RunWith(AndroidJUnit4::class)
class GoblinHoursAddendumSmokeTest {

    @Test
    fun goblinHoursClock_yieldsShorterFollowUpThanDaytimeClock() {
        runBlocking { runSmokeTest() }
    }

    private suspend fun runSmokeTest() {
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

        val zone = ZoneId.systemDefault()
        val nightClock = Clock.fixed(
            LocalDateTime.of(2026, 5, 9, GOBLIN_HOUR, 0).atZone(zone).toInstant(),
            zone,
        )
        val dayClock = Clock.fixed(
            LocalDateTime.of(2026, 5, 9, DAYTIME_HOUR, 0).atZone(zone).toInstant(),
            zone,
        )

        val engine = LiteRtLmEngine(
            modelPath = modelPath,
            backend = BackendChoice.Cpu,
            audioBackend = BackendChoice.Cpu,
            cacheDir = cacheDir.absolutePath,
        )

        engine.use {
            it.initialize()
            val nightFollowUp =
                runOneCall(ForegroundInference(it, cacheDir, clock = nightClock, zoneId = zone), chunk, "night")
            val dayFollowUp =
                runOneCall(ForegroundInference(it, cacheDir, clock = dayClock, zoneId = zone), chunk, "day")

            assertTrue("night follow-up must be non-blank", nightFollowUp.isNotBlank())
            assertTrue("day follow-up must be non-blank", dayFollowUp.isNotBlank())
            assertNotEquals(
                "Same audio + persona under goblin vs day clock must produce different follow-ups",
                nightFollowUp.trim(),
                dayFollowUp.trim(),
            )

            val nightChars = nightFollowUp.trim().length
            val dayChars = dayFollowUp.trim().length
            android.util.Log.i(TAG, "=== chars: night=$nightChars day=$dayChars delta=${dayChars - nightChars} ===")
        }
    }

    private suspend fun runOneCall(inference: ForegroundInference, chunk: AudioChunk, tag: String): String {
        val result = inference.runForegroundCall(chunk, Persona.WITNESS)
        assertTrue("$tag capture must succeed; was $result", result is ForegroundResult.Success)
        val success = result as ForegroundResult.Success
        android.util.Log.i(TAG, "=== $tag follow-up (${success.elapsedMs}ms) ===")
        android.util.Log.i(TAG, success.followUp)
        return success.followUp
    }

    /** Mono WAV reader (PCM_S16LE or IEEE_FLOAT). Mirrors `PerCapturePersonaSmokeTest`. */
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
                        audioFormat == FMT_IEEE_FLOAT && bitsPerSample == 32 ->
                            FloatArray(chunkSize / BYTES_PER_FLOAT).also { out -> buf.asFloatBuffer().get(out) }

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
        const val TAG = "VestigeGoblinHoursAddendum"
        const val GOBLIN_HOUR = 3
        const val DAYTIME_HOUR = 12
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
