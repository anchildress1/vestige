package dev.anchildress1.vestige

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.vestige.corpus.InferenceBackendArg
import dev.anchildress1.vestige.inference.LiteRtLmEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Smoke test for [LiteRtLmEngine.streamText]. Logs per-chunk length and time-to-first so the
 * operator can confirm SDK emission shape on device.
 *
 *   adb push gemma-4-E4B-it.litertlm /data/local/tmp/
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -PmodelPath=/data/local/tmp/gemma-4-E4B-it.litertlm \
 *     -PinferenceBackend=gpu \
 *     -Pandroid.testInstrumentationRunnerArguments.class=dev.anchildress1.vestige.LiteRtLmStreamingTextSmokeTest
 */
@RunWith(AndroidJUnit4::class)
class LiteRtLmStreamingTextSmokeTest {

    @Test
    fun streamText_emitsIncrementally_andCompletes() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val modelPath = args.getString("modelPath")
        assumeTrue("modelPath instrumentation argument not provided", modelPath != null)
        val modelFile = File(modelPath!!)
        assumeTrue("Model file not found at $modelPath", modelFile.exists() && modelFile.canRead())

        val backend = InferenceBackendArg.resolve(args)
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val engine = LiteRtLmEngine(
            modelPath = modelPath,
            backend = backend,
            cacheDir = cacheDir.absolutePath,
        )

        engine.use {
            it.initialize()

            val started = System.nanoTime()
            var firstChunkAtNs: Long? = null
            val chunks = mutableListOf<String>()

            it.streamText("You are a smoke-test probe. Follow the instruction exactly.", PROMPT).collect { chunk ->
                if (firstChunkAtNs == null) firstChunkAtNs = System.nanoTime()
                chunks += chunk
            }

            val totalElapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
            val timeToFirstMs = firstChunkAtNs?.let { ns -> (ns - started) / NANOS_PER_MILLI } ?: -1L

            chunks.forEachIndexed { idx, chunk ->
                android.util.Log.i(
                    TAG,
                    "chunk[$idx] len=${chunk.length} first=${chunk.take(SAMPLE_PREVIEW_CHARS)}…",
                )
            }
            val joined = chunks.joinToString(separator = "")
            android.util.Log.i(
                TAG,
                "=== streamText summary: backend=$backend chunks=${chunks.size} " +
                    "first_chunk_ms=$timeToFirstMs total_ms=$totalElapsedMs " +
                    "joined_len=${joined.length} ===",
            )
            android.util.Log.i(TAG, "joined preview: ${joined.take(SAMPLE_PREVIEW_CHARS)}")

            assertTrue("streamText emitted no chunks", chunks.isNotEmpty())
            assertTrue("streamText emitted only blanks (joined=${joined.length}c)", joined.isNotBlank())
        }
    }

    private companion object {
        const val TAG = "VestigeStreamSmoke"
        const val SAMPLE_PREVIEW_CHARS = 64
        const val NANOS_PER_MILLI = 1_000_000L
        const val PROMPT = "Reply with exactly one short sentence: name the season after summer."
    }
}
