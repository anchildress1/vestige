package dev.anchildress1.vestige

import android.os.Debug
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
 * On-device RAM + wall-clock probe for the sequential single-session 3-lens worker (ADR-008
 * §Addendum 2026-05-17; STT-F). Measures PSS before/after one full extraction and logs the
 * realized wall-clock. Numbers feed the STT-F record; there are no hard pass/fail thresholds.
 * Post-fix this must show 3/3 lenses parsed (the concurrent run showed 1/3 — single-session SDK).
 *
 * Run (each line is one shell token — never let the class arg wrap mid-name):
 *   adb logcat -c
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -PmodelPath=/data/local/tmp/gemma-4-E4B-it.litertlm \
 *     -PinferenceBackend=gpu \
 *     -Pandroid.testInstrumentationRunnerArguments.class=dev.anchildress1.vestige.RamWallClockProbeTest
 *   adb logcat -d -s VestigeLiteRtLm
 */
@RunWith(AndroidJUnit4::class)
class RamWallClockProbeTest {

    @Test
    fun measureRamAndWallClock() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val modelPath = args.getString("modelPath")
        assumeTrue("modelPath instrumentation argument not provided", modelPath != null)
        val modelFile = File(modelPath!!)
        assumeTrue("Model file not found at $modelPath", modelFile.exists() && modelFile.canRead())

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val backend = InferenceBackendArg.resolve(args)

        log("backend=$backend model=$modelPath")

        val engine = LiteRtLmEngine(
            modelPath = modelPath,
            backend = backend,
            cacheDir = ctx.cacheDir.absolutePath,
        )

        engine.use {
            it.initialize()
            log("engine initialized")

            val pssBefore = pssKb()
            log("pss_before_kb=$pssBefore")

            val started = System.nanoTime()
            val result = BackgroundExtractionWorker(
                engine = it,
                resolver = DefaultConvergenceResolver(),
            ).extract(
                BackgroundExtractionRequest(
                    entryText = ENTRY_TEXT,
                    capturedAt = CAPTURED_AT,
                ),
            )
            val wallClockMs = (System.nanoTime() - started) / 1_000_000L

            val pssAfter = pssKb()
            val pssDeltaKb = pssAfter - pssBefore

            log("wall_clock_ms=$wallClockMs")
            log("pss_after_kb=$pssAfter pss_delta_kb=$pssDeltaKb")
            log("model_calls=${result.modelCallCount} parsed=${result.lensResults.count { it.extraction != null }}/3")
            result.lensResults.forEach { lens ->
                log(
                    "lens=${lens.lens} elapsed_ms=${lens.elapsedMs} " +
                        "attempts=${lens.attemptCount} parsed=${lens.extraction != null}",
                )
            }
            val outcome = when (result) {
                is BackgroundExtractionResult.Success -> "outcome=SUCCESS template=${result.templateLabel}"
                is BackgroundExtractionResult.TimedOut -> "outcome=TIMED_OUT"
                is BackgroundExtractionResult.Failed -> "outcome=FAILED last_error=${result.lastError}"
            }
            log(outcome)
        }
    }

    private fun pssKb(): Long {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss.toLong()
    }

    private fun log(msg: String) {
        android.util.Log.i(TAG, msg)
    }

    private companion object {
        const val TAG = "VestigeLiteRtLm"

        val CAPTURED_AT: ZonedDateTime =
            ZonedDateTime.parse("2026-05-17T12:00:00-05:00[America/Chicago]")

        const val ENTRY_TEXT =
            "went into the sink at noon completely fine by 1pm i was gone " +
                "not tired exactly more like the battery just pulled out " +
                "three hours later I'm starting to feel like a person again"
    }
}
