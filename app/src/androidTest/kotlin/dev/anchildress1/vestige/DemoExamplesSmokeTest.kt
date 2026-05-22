package dev.anchildress1.vestige

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.vestige.corpus.InferenceBackendArg
import dev.anchildress1.vestige.inference.BackgroundExtractionRequest
import dev.anchildress1.vestige.inference.BackgroundExtractionResult
import dev.anchildress1.vestige.inference.BackgroundExtractionWorker
import dev.anchildress1.vestige.inference.DefaultConvergenceResolver
import dev.anchildress1.vestige.inference.HistoryChunk
import dev.anchildress1.vestige.inference.LensResult
import dev.anchildress1.vestige.inference.LiteRtLmEngine
import dev.anchildress1.vestige.model.TemplateLabel
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
 * Device smoke for the three demo transcripts that triggered the current regressions.
 *
 * No mic, no audio fixture, no human performance art. The test drives:
 * - background three-lens extraction with real GPU inference
 *
 * Run:
 *
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -PmodelPath=/data/local/tmp/gemma-4-E4B-it.litertlm \
 *     -PinferenceBackend=gpu \
 *     -Pandroid.testInstrumentationRunnerArguments.class=dev.anchildress1.vestige.DemoExamplesSmokeTest
 */
@RunWith(AndroidJUnit4::class)
class DemoExamplesSmokeTest {

    @Test
    fun backgroundExtractionSurfacesRealEvidenceOnDemoExamples() = runBlocking {
        CASES.forEach { example ->
            withEngine { engine ->
                val worker = BackgroundExtractionWorker(
                    engine = engine,
                    resolver = DefaultConvergenceResolver(),
                )
                val result = worker.extract(
                    BackgroundExtractionRequest(
                        entryText = example.entryText,
                        capturedAt = example.capturedAt,
                        retrievedHistory = example.retrievedHistory,
                    ),
                )
                logBackground(example, result)
                assertTrue(
                    "${example.id}: background extraction must succeed; was $result",
                    result is BackgroundExtractionResult.Success,
                )
                result as BackgroundExtractionResult.Success
                assertEquals(
                    "${example.id}: every lens must parse",
                    EXPECTED_LENS_COUNT,
                    result.lensResults.count { it.extraction != null },
                )

                example.expectedTemplateLabel?.let { expected ->
                    assertEquals(
                        "${example.id}: template label must match the intended demo shape",
                        expected,
                        result.templateLabel,
                    )
                }

                example.expectedPatternId?.let { patternId ->
                    val recurrence = result.resolved.fields[KEY_RECURRENCE]?.value as? String
                    assertEquals(
                        "${example.id}: recurrence must use the retrieved pattern id",
                        patternId,
                        recurrence,
                    )
                }

                if (example.expectedResolvedTags.isNotEmpty()) {
                    val tags = (result.resolved.fields[KEY_TAGS]?.value as? List<*>)?.mapNotNull {
                        it as? String
                    }.orEmpty()
                    assertTrue(
                        "${example.id}: expected one of ${example.expectedResolvedTags} " +
                            "in resolved tags; got $tags",
                        example.expectedResolvedTags.any { expected ->
                            tags.any { tag ->
                                tag.normalizedDemoToken() ==
                                    expected.normalizedDemoToken()
                            }
                        },
                    )
                }
            }
        }
    }

    private suspend fun withEngine(block: suspend (LiteRtLmEngine) -> Unit) {
        val args = InstrumentationRegistry.getArguments()
        val modelPath = args.getString("modelPath")
        assumeTrue("modelPath instrumentation argument not provided", modelPath != null)
        val modelFile = File(modelPath!!)
        assumeTrue("Model file not found at $modelPath", modelFile.exists() && modelFile.canRead())

        val engine = LiteRtLmEngine(
            modelPath = modelPath,
            backend = InferenceBackendArg.resolve(args),
            cacheDir = appCacheDir().absolutePath,
        )
        engine.use {
            it.initialize()
            block(it)
        }
    }

    private fun appCacheDir(): File = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    private fun String.normalizedDemoToken(): String = lowercase()
        .replace(Regex("[^a-z0-9]+"), "")

    private fun logBackground(example: DemoExample, result: BackgroundExtractionResult) {
        val output = JSONObject()
            .put("type", result::class.simpleName)
            .put("elapsedMs", result.totalElapsedMs)
            .put("modelCallCount", result.modelCallCount)
            .put("parsedLensCount", result.lensResults.count { it.extraction != null })
            .put("lenses", JSONArray(result.lensResults.map(::lensJson)))
            .put(
                "resolved",
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
                },
            )
        android.util.Log.i(
            TAG,
            "DEMO_BACKGROUND ${example.id} " +
                JSONObject()
                    .put("input", example.inputJson())
                    .put("output", output)
                    .toString(),
        )
    }

    private fun lensJson(lens: LensResult): JSONObject = JSONObject()
        .put("lens", lens.lens.name)
        .put("parsed", lens.extraction != null)
        .put("attemptCount", lens.attemptCount)
        .put("elapsedMs", lens.elapsedMs)
        .put("lastError", lens.lastError)
        .put("fields", jsonValue(lens.extraction?.fields))
        .put("flags", jsonValue(lens.extraction?.flags))

    private fun jsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL

        is Map<*, *> -> JSONObject().also { json ->
            value.forEach { (key, child) -> json.put(key.toString(), jsonValue(child)) }
        }

        is Iterable<*> -> JSONArray(value.map(::jsonValue))

        is Array<*> -> JSONArray(value.map(::jsonValue))

        else -> value
    }

    private data class DemoExample(
        val id: String,
        val capturedAt: ZonedDateTime,
        val entryText: String,
        val retrievedHistory: List<HistoryChunk> = emptyList(),
        val expectedTemplateLabel: TemplateLabel? = null,
        val expectedPatternId: String? = null,
        val expectedResolvedTags: Set<String> = emptySet(),
    ) {
        fun inputJson(): JSONObject = JSONObject()
            .put("capturedAt", capturedAt.toString())
            .put("entryText", entryText)
            .put(
                "retrievedHistory",
                JSONArray(
                    retrievedHistory.map { history ->
                        JSONObject()
                            .put("patternId", history.patternId)
                            .put("text", history.text)
                    },
                ),
            )
    }

    private companion object {
        const val TAG = "VestigeDemoSmoke"
        const val EXPECTED_LENS_COUNT = 3
        const val KEY_RECURRENCE = "recurrence_link"
        const val KEY_TAGS = "tags"
        const val PACKAGE_PATTERN_ID =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val COUCH_PATTERN_ID =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val CASES = listOf(
            DemoExample(
                id = "editor-hollow-thing",
                capturedAt = ZonedDateTime.parse(
                    "2026-05-19T20:19:09-04:00[America/New_York]",
                ),
                entryText =
                "after the all hands i did the hollow thing again my coffee went cold on " +
                    "the desk and the thing i was going to do right after that kind of " +
                    "vaped while reading i had three tabs open i knew with the three tabs " +
                    "are four and they're still sitting there open",
                expectedTemplateLabel = TemplateLabel.AFTERMATH,
                expectedResolvedTags = setOf(
                    "hollow-routine",
                    "hollow-thing",
                    "tabs",
                    "tabs-open",
                    "meeting",
                    "all-hands",
                ),
            ),
            DemoExample(
                id = "editor-package-loop",
                capturedAt = ZonedDateTime.parse(
                    "2026-05-19T20:20:09-04:00[America/New_York]",
                ),
                entryText =
                "said i would drop the package off today. drive past ups on my route. " +
                    "spent twenty minutes googling whether the thing is even worth " +
                    "returning. it is. label is still on the counter.",
                retrievedHistory = listOf(
                    HistoryChunk(
                        patternId = PACKAGE_PATTERN_ID,
                        text =
                        "last tuesday i said i would drop the package off today, drove " +
                            "past ups again, and left the label on the counter.",
                    ),
                ),
                expectedPatternId = PACKAGE_PATTERN_ID,
                expectedResolvedTags = setOf(
                    "package-drop-off",
                    "package",
                    "ups",
                    "googling",
                    "label-on-counter",
                ),
            ),
            DemoExample(
                id = "editor-couch-loop",
                capturedAt = ZonedDateTime.parse(
                    "2026-05-19T20:21:25-04:00[America/New_York]",
                ),
                entryText =
                "spent an hour and a half comparing couches. dimensions, reviews, lead " +
                    "time, return policy. made a spreadsheet. did not buy a couch. " +
                    "twelve rows.",
                retrievedHistory = listOf(
                    HistoryChunk(
                        patternId = COUCH_PATTERN_ID,
                        text =
                        "last month i spent an hour comparing couches in a spreadsheet " +
                            "and still did not buy one.",
                    ),
                ),
                expectedTemplateLabel = TemplateLabel.DECISION_SPIRAL,
                expectedPatternId = COUCH_PATTERN_ID,
                expectedResolvedTags = setOf("couch", "spreadsheet", "comparing"),
            ),
        )
    }
}
