package dev.anchildress1.vestige

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.vestige.corpus.InferenceBackendArg
import dev.anchildress1.vestige.inference.BackgroundExtractionRequest
import dev.anchildress1.vestige.inference.BackgroundExtractionResult
import dev.anchildress1.vestige.inference.BackgroundExtractionWorker
import dev.anchildress1.vestige.inference.DefaultConvergenceResolver
import dev.anchildress1.vestige.inference.LiteRtLmEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZonedDateTime

/**
 * One-entry manual repro harness for background extraction. Runs the supplied entry text through
 * the full three-lens worker and logs each lens's parse status plus full raw payload so parse
 * failures stop being folklore.
 *
 * Example:
 *
 *   adb logcat -c
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -PmodelPath=/data/local/tmp/gemma-4-E4B-it.litertlm \
 *     -PinferenceBackend=gpu \
 *     -Pandroid.testInstrumentationRunnerArguments.class=\
 *dev.anchildress1.vestige.SingleEntryBackgroundExtractionSmokeTest \
 *     -Pandroid.testInstrumentationRunnerArguments.entryText="went into the sink at noon \
 *completely fine by 1pm i was gone not tired exactly more like the battery just pulled out \
 *three hours later I'm starting to feel like a person again" \
 *     -Pandroid.testInstrumentationRunnerArguments.capturedAt="2026-05-17T12:00:00-04:00[America/New_York]"
 *   adb logcat -d -s VestigeSingleEntryBg
 */
@RunWith(AndroidJUnit4::class)
class SingleEntryBackgroundExtractionSmokeTest {

    @Test
    fun runSingleEntry() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val modelPath = args.getString("modelPath")
        val entryText = args.getString("entryText")
        val capturedAtRaw = args.getString("capturedAt")

        assumeTrue("modelPath instrumentation argument not provided", modelPath != null)
        assumeTrue("entryText instrumentation argument not provided", entryText != null)

        val modelFile = File(modelPath!!)
        assumeTrue("Model file not found at $modelPath", modelFile.exists() && modelFile.canRead())

        val capturedAt = capturedAtRaw?.let(ZonedDateTime::parse) ?: DEFAULT_CAPTURED_AT
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val backend = InferenceBackendArg.resolve(args)
        android.util.Log.i(TAG, "single-entry backend=$backend capturedAt=$capturedAt")
        android.util.Log.i(TAG, "single-entry text=$entryText")

        val engine = LiteRtLmEngine(
            modelPath = modelPath,
            backend = backend,
            cacheDir = cacheDir.absolutePath,
        )

        engine.use {
            it.initialize()
            val worker = BackgroundExtractionWorker(engine = it, resolver = DefaultConvergenceResolver())
            val result = worker.extract(
                BackgroundExtractionRequest(
                    entryText = entryText!!,
                    capturedAt = capturedAt,
                ),
            )
            logResult(result)
        }
    }

    private fun logResult(result: BackgroundExtractionResult) {
        android.util.Log.i(
            TAG,
            "result type=${result::class.simpleName} elapsed=${result.totalElapsedMs}ms " +
                "parsed=${result.lensResults.count { it.extraction != null }}/3 modelCalls=${result.modelCallCount}",
        )
        result.lensResults.forEach { lens ->
            android.util.Log.i(
                TAG,
                "lens=${lens.lens} parsed=${lens.extraction != null} attempts=${lens.attemptCount} " +
                    "err=${lens.lastError ?: "-"} elapsed=${lens.elapsedMs}ms",
            )
            android.util.Log.i(TAG, "RAW lens=${lens.lens} >>>${lens.rawResponse}<<<")
        }
    }

    private companion object {
        const val TAG = "VestigeSingleEntryBg"
        val DEFAULT_CAPTURED_AT: ZonedDateTime =
            ZonedDateTime.parse("2026-05-17T12:00:00-04:00[America/New_York]")
    }
}
