package dev.anchildress1.vestige

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.vestige.corpus.InferenceBackendArg
import dev.anchildress1.vestige.inference.BackgroundExtractionRequest
import dev.anchildress1.vestige.inference.BackgroundExtractionResult
import dev.anchildress1.vestige.inference.BackgroundExtractionWorker
import dev.anchildress1.vestige.inference.DefaultConvergenceResolver
import dev.anchildress1.vestige.inference.LiteRtLmEngine
import dev.anchildress1.vestige.inference.PromptComposer
import dev.anchildress1.vestige.model.Lens
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Prompt-tuning harness for background extraction. Pure observation: runs the configured lenses over
 * a few fixed entries and dumps the resolved (converged) output per entry. No assertions — nothing
 * fails while you tune. Edit the prompt `.txt` modules or the `lenses` list, rerun, read the log.
 *
 * Loads the engine once (not per entry). Set `lenses` to one lens to tune it in isolation, or all
 * three to watch convergence.
 *
 * Run:
 *
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -PmodelPath=/data/local/tmp/gemma-4-E4B-it.litertlm \
 *     -PinferenceBackend=gpu \
 *     -Pandroid.testInstrumentationRunnerArguments.class=dev.anchildress1.vestige.SinglePassExtractionTuningTest
 *
 * Read output:
 *
 *   adb logcat -s VestigeTuning
 */
@RunWith(AndroidJUnit4::class)
class SinglePassExtractionTuningTest {

    @Test
    fun dumpsRawAndParsedForEachEntry() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val modelPath = args.getString("modelPath")
        assumeTrue("modelPath instrumentation argument not provided", modelPath != null)
        val modelFile = File(modelPath!!)
        assumeTrue("Model file not found at $modelPath", modelFile.exists() && modelFile.canRead())

        val engine = LiteRtLmEngine(
            modelPath = modelPath,
            backend = InferenceBackendArg.resolve(args),
            cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.absolutePath,
        )
        engine.use {
            it.initialize()
            val worker = BackgroundExtractionWorker(
                engine = it,
                resolver = DefaultConvergenceResolver(),
                lenses = listOf(Lens.LITERAL, Lens.INFERENTIAL, Lens.SKEPTICAL),
            )

            // Sanity marker for which prompt version ran (the full text lives in the .txt sources).
            val composed = PromptComposer.compose(Lens.INFERENTIAL, ENTRIES.first().text)
            android.util.Log.i(
                TAG,
                "TUNING_PROMPT systemChars=${composed.systemInstruction.length} tokens~=${composed.tokenEstimate}",
            )

            ENTRIES.forEach { entry ->
                val result = worker.extract(
                    BackgroundExtractionRequest(
                        entryText = entry.text,
                        capturedAt = entry.capturedAt,
                    ),
                )
                logTuning(entry, result)
            }
        }
    }

    private fun logTuning(entry: TuningEntry, result: BackgroundExtractionResult) {
        val out = JSONObject()
            .put("id", entry.id)
            .put("type", result::class.simpleName)
            .put("modelCalls", result.modelCallCount)
            .put("lensesParsed", result.lensResults.count { it.extraction != null })
            .put("elapsedMs", result.totalElapsedMs)
            .put(
                "lensMs",
                JSONArray(result.lensResults.map { "${it.lens.name}=${it.elapsedMs}ms/${it.attemptCount}att" }),
            )
            .put(
                "lensEnergyShift",
                JSONArray(
                    result.lensResults.map { lr ->
                        "${lr.lens.name}=${lr.extraction?.fields?.get("energy_descriptor")}/" +
                            "${lr.extraction?.fields?.get("state_shift")}"
                    },
                ),
            )
        if (result is BackgroundExtractionResult.Success) {
            out.put("templateLabel", result.templateLabel.name)
            out.put(
                "resolved",
                jsonValue(
                    result.resolved.fields.mapValues { (_, field) ->
                        mapOf(
                            "value" to field.value,
                            "verdict" to field.verdict.name,
                            "flags" to field.flags,
                            "sourceLens" to field.sourceLens?.name,
                        )
                    },
                ),
            )
        } else {
            out.put("lastError", (result as? BackgroundExtractionResult.Failed)?.lastError ?: JSONObject.NULL)
        }
        android.util.Log.i(TAG, "TUNING $out")
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

    private data class TuningEntry(val id: String, val text: String, val capturedAt: java.time.ZonedDateTime)

    private companion object {
        const val TAG = "VestigeTuning"
        private val ZONE = ZoneId.of("America/New_York")

        // A fixed diagnostic slice, inlined because the seeder lives in the debug source set and is
        // not visible from androidTest.
        val ENTRIES = listOf(
            TuningEntry(
                id = "standup-crash-cycle",
                text = "I was completely fine going into the standup but crashed hard within about twenty " +
                    "minutes. Couldn't get back to the doc for the rest of the day. Then somehow wired " +
                    "until 2am. That's the whole cycle in one day.",
                capturedAt = Instant.parse("2026-05-07T18:42:00Z").atZone(ZONE),
            ),
            TuningEntry(
                id = "tuesday-concrete-shoes",
                text = "Every Tuesday meeting does the same thing to me. I go in okay and come out with " +
                    "what I can only describe as concrete shoes. Everything feels heavier and slower " +
                    "for the rest of the afternoon, and I never seem to account for it.",
                capturedAt = Instant.parse("2026-05-05T14:10:00Z").atZone(ZONE),
            ),
            TuningEntry(
                id = "doc-done-one-sitting",
                text = "Actually got the whole doc done in one sitting today and I didn't expect that at all. " +
                    "I kept waiting for the stall to kick in but it never did. Weird but I'll take it. " +
                    "Not sure what was different.",
                capturedAt = Instant.parse("2026-05-08T10:24:00Z").atZone(ZONE),
            ),
            TuningEntry(
                id = "awake-2am-spinning",
                text = "Still awake at 2am, not anxious exactly, just can't seem to land. Brain keeps spinning " +
                    "on things that genuinely don't need to be thought about right now. I don't even know " +
                    "if this is productive or just restless. Hard to tell the difference tonight.",
                capturedAt = Instant.parse("2026-05-09T06:13:00Z").atZone(ZONE),
            ),
            TuningEntry(
                id = "shipped-then-wall",
                text = "Shipped the feature this afternoon and then immediately hit a wall. Couldn't start " +
                    "anything else for like two hours, just sat there staring at the next ticket. I don't " +
                    "know why completing things does this to me but it happens every single time.",
                capturedAt = Instant.parse("2026-05-13T21:08:00Z").atZone(ZONE),
            ),
            TuningEntry(
                id = "migration-rewrite-loop",
                text = "Decided to rewrite the migration again tonight. This is the third time this week I've " +
                    "restarted it with completely different reasoning each time. I keep convincing myself " +
                    "the new approach is obviously better. I think I might just be spinning and calling it " +
                    "progress.",
                capturedAt = Instant.parse("2026-05-14T16:45:00Z").atZone(ZONE),
            ),
            TuningEntry(
                id = "committing-this-version",
                text = "Rewrote the whole thing again and this time it actually feels right. But I said that " +
                    "last time too so I don't fully trust myself on this. Different structure at least. I'm " +
                    "committing to this version even if it costs me another day.",
                capturedAt = Instant.parse("2026-05-16T11:05:00Z").atZone(ZONE),
            ),
            TuningEntry(
                id = "audit-double-check",
                text = "Audit cycle started today and I reviewed everything twice before sending anything. That " +
                    "kind of second-guessing slows everything down to a crawl. Took me twice as long as it " +
                    "should have and I'm still not confident it was right. That's the worst combination.",
                capturedAt = Instant.parse("2026-05-18T19:22:00Z").atZone(ZONE),
            ),
            TuningEntry(
                id = "crashed-3pm-no-warning",
                text = "Crashed at 3pm completely out of nowhere. No warning, no buildup, just suddenly couldn't " +
                    "think. I was functional an hour earlier and then just gone. Had to give up on the rest " +
                    "of the afternoon. I don't know what happened.",
                capturedAt = Instant.parse("2026-05-20T19:00:00Z").atZone(ZONE),
            ),
            TuningEntry(
                id = "wired-tired-third-night",
                text = "Wired-tired again tonight and I don't know which is worse. Body wants sleep, brain just " +
                    "refuses. Lying down doesn't help. Not anxious about anything specific, just running at " +
                    "the wrong frequency.",
                capturedAt = Instant.parse("2026-05-05T12:00:00Z").atZone(ZONE),
            ),
            TuningEntry(
                id = "anxious-tired",
                text = "Anxious-tired is the only way I can describe what this is. Lying down doesn't count as " +
                    "rest when my brain is still processing everything. Slept but woke up like I hadn't slept " +
                    "at all.",
                capturedAt = Instant.parse("2026-05-05T18:00:00Z").atZone(ZONE),
            ),
            TuningEntry(
                id = "both-tanks-empty",
                text = "Can't sleep, can't focus, both tanks empty at the same time. I don't know how that works " +
                    "but here I am at 1am, fully depleted and fully awake. Completely contradictory.",
                capturedAt = Instant.parse("2026-05-07T00:00:00Z").atZone(ZONE),
            ),
        )
    }
}
