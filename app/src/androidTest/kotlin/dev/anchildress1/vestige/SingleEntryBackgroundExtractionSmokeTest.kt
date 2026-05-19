package dev.anchildress1.vestige

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.vestige.corpus.InferenceBackendArg
import dev.anchildress1.vestige.inference.BackendChoice
import dev.anchildress1.vestige.inference.BackgroundExtractionRequest
import dev.anchildress1.vestige.inference.BackgroundExtractionResult
import dev.anchildress1.vestige.inference.BackgroundExtractionWorker
import dev.anchildress1.vestige.inference.DefaultConvergenceResolver
import dev.anchildress1.vestige.inference.LensResult
import dev.anchildress1.vestige.inference.LiteRtLmEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        android.util.Log.i(TAG, "single-entry backend=${backend.label} capturedAt=$capturedAt")
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
            logResult(
                result = result,
                modelPath = modelPath,
                backend = backend.label,
                entryText = entryText,
                capturedAt = capturedAt.toString(),
            )
            assertTrue(
                "single-entry extraction must succeed; was $result",
                result is BackgroundExtractionResult.Success,
            )
            assertEquals(
                "single-entry smoke must parse every lens",
                EXPECTED_LENS_COUNT,
                result.lensResults.count { lens -> lens.extraction != null },
            )
        }
    }

    private fun logResult(
        result: BackgroundExtractionResult,
        modelPath: String,
        backend: String,
        entryText: String,
        capturedAt: String,
    ) {
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
        val inputJson = JSONObject()
            .put("modelPath", modelPath)
            .put("backend", backend)
            .put("capturedAt", capturedAt)
            .put("entryText", entryText)
        val outputJson = JSONObject()
            .put("resultType", result::class.simpleName)
            .put("elapsedMs", result.totalElapsedMs)
            .put("modelCallCount", result.modelCallCount)
            .put("parsedLensCount", result.lensResults.count { it.extraction != null })
            .put("resolved", resolvedJson(result))
        android.util.Log.i(TAG, "$JSON_INPUT_PREFIX$inputJson")
        android.util.Log.i(TAG, "$JSON_OUTPUT_PREFIX$outputJson")
        result.lensResults.forEach { lens ->
            android.util.Log.i(TAG, "$JSON_LENS_PREFIX${lensResultJson(lens)}")
        }
        logChunkedJsonReport(
            JSONObject()
                .put("input", inputJson)
                .put("output", outputJson.put("lenses", JSONArray(result.lensResults.map(::lensResultJson)))),
        )
    }

    private fun lensResultJson(lens: LensResult): JSONObject = JSONObject()
        .put("lens", lens.lens.name)
        .put("parsed", lens.extraction != null)
        .put("attemptCount", lens.attemptCount)
        .put("elapsedMs", lens.elapsedMs)
        .put("lastError", lens.lastError)
        .put("rawResponse", lens.rawResponse)
        .put(
            "extraction",
            lens.extraction?.let { extraction ->
                JSONObject()
                    .put("fields", jsonValue(extraction.fields))
                    .put("flags", jsonValue(extraction.flags))
            },
        )

    private fun resolvedJson(result: BackgroundExtractionResult): Any =
        if (result is BackgroundExtractionResult.Success) {
            jsonValue(
                result.resolved.fields.mapValues { (_, field) ->
                    mapOf(
                        "value" to field.value,
                        "verdict" to field.verdict.name,
                        "flags" to field.flags,
                        "sourceLens" to field.sourceLens?.name,
                    )
                },
            )
        } else {
            JSONObject.NULL
        }

    private fun jsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL

        is Map<*, *> -> JSONObject().also { json ->
            value.forEach { (key, child) -> json.put(key.toString(), jsonValue(child)) }
        }

        is Iterable<*> -> JSONArray(value.map(::jsonValue))

        is Array<*> -> JSONArray(value.map(::jsonValue))

        else -> value
    }

    private fun logChunkedJsonReport(report: JSONObject) {
        val pretty = report.toString(JSON_INDENT_SPACES)
        pretty.chunked(LOG_CHUNK_SIZE).forEachIndexed { index, chunk ->
            android.util.Log.i(TAG, "jsonPrettyChunk[${index + 1}]=$chunk")
        }
    }

    private val BackendChoice.label: String
        get() = when (this) {
            BackendChoice.Gpu -> "GPU"
            is BackendChoice.Npu -> "NPU"
        }

    private companion object {
        const val TAG = "VestigeSingleEntryBg"
        const val EXPECTED_LENS_COUNT = 3
        const val JSON_INDENT_SPACES = 2
        const val JSON_INPUT_PREFIX = "SMOKE_INPUT_JSON="
        const val JSON_OUTPUT_PREFIX = "SMOKE_OUTPUT_JSON="
        const val JSON_LENS_PREFIX = "SMOKE_LENS_JSON="
        const val LOG_CHUNK_SIZE = 3000
        val DEFAULT_CAPTURED_AT: ZonedDateTime =
            ZonedDateTime.parse("2026-05-17T12:00:00-04:00[America/New_York]")
    }
}
