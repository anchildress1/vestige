package dev.anchildress1.vestige

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.vestige.corpus.CorpusEntry
import dev.anchildress1.vestige.corpus.CorpusManifest
import dev.anchildress1.vestige.corpus.InferenceBackendArg
import dev.anchildress1.vestige.inference.BackgroundExtractionRequest
import dev.anchildress1.vestige.inference.BackgroundExtractionResult
import dev.anchildress1.vestige.inference.BackgroundExtractionWorker
import dev.anchildress1.vestige.inference.DefaultConvergenceResolver
import dev.anchildress1.vestige.inference.LiteRtLmEngine
import dev.anchildress1.vestige.model.ResolvedExtraction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * STT-C — tag-extraction stability per `docs/stories/phase-2-core-loop.md` §Story 2.9 and
 * `docs/sample-data-scenarios.md` §STT-C. Runs every manifest entry through
 * [BackgroundExtractionWorker] [runsPerEntry] times (default 3), pools the resolver's emitted
 * tag set per run, and asserts that the (entry, tag) pairs which appear in every run are at
 * least [STABILITY_THRESHOLD] of all observed (entry, tag) pairs.
 *
 * Manual, on-device, network-sealed. Push artifacts then run:
 *
 *   adb push gemma-4-E4B-it.litertlm /data/local/tmp/
 *   adb push docs/stt-c-manifest.example.txt /data/local/tmp/stt-c-manifest.txt
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -PmodelPath=/data/local/tmp/gemma-4-E4B-it.litertlm \
 *     -PmanifestPath=/data/local/tmp/stt-c-manifest.txt \
 *     -PrunsPerEntry=3 \
 *     -Pandroid.testInstrumentationRunnerArguments.class=dev.anchildress1.vestige.SttCTagStabilityTest
 *
 * Missing args → [assumeTrue] skips so CI without artifacts stays green. Story 2.9 defines the
 * gate as stability across **three** runs per dump, so [runsPerEntry] must be ≥ 3 — below that
 * the assertion would over-pass against a thinner sample. The default 3 runs × 18 canonical
 * corpus entries × ~3 lens calls per run is a long-running on-device suite; expect tens of
 * minutes on E4B CPU. The harness rejects cherry-picked manifests so the phase gate cannot pass
 * on a toy subset, and entries that emit zero tags across all runs hard-fail rather than
 * silently dropping out of the stability ratio.
 */
@RunWith(AndroidJUnit4::class)
class SttCTagStabilityTest {

    @Test
    fun resolvedTagsAreStableAcrossRepeatRuns() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val modelPath = args.getString("modelPath")
        val manifestPath = args.getString("manifestPath")
        val runsPerEntry = args.getString("runsPerEntry")?.toIntOrNull() ?: DEFAULT_RUNS_PER_ENTRY
        assumeTrue("modelPath instrumentation argument not provided", modelPath != null)
        assumeTrue("manifestPath instrumentation argument not provided", manifestPath != null)
        require(runsPerEntry >= MIN_RUNS_PER_ENTRY) {
            "runsPerEntry must be >= $MIN_RUNS_PER_ENTRY to measure stability (got $runsPerEntry)"
        }

        val modelFile = File(modelPath!!)
        val manifestFile = File(manifestPath!!)
        assumeTrue("Model file not found at $modelPath", modelFile.exists() && modelFile.canRead())
        assumeTrue("Manifest not found at $manifestPath", manifestFile.exists() && manifestFile.canRead())

        val corpus = CorpusManifest.load(manifestFile)
        StopAndTestCorpusRules.requireCanonicalSttCCorpus(corpus.map(CorpusEntry::id))

        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val backend = InferenceBackendArg.resolve(args)
        android.util.Log.i(TAG, "STT-C using backend=$backend runsPerEntry=$runsPerEntry")
        val engine = LiteRtLmEngine(
            modelPath = modelPath,
            backend = backend,
            cacheDir = cacheDir.absolutePath,
        )

        engine.use {
            it.initialize()
            val worker = BackgroundExtractionWorker(engine = it, resolver = DefaultConvergenceResolver())

            val perEntry = corpus.map { entry -> runEntry(worker, entry, runsPerEntry) }
            val stability = summarizeStability(perEntry, runsPerEntry)
            logSummary(perEntry, stability, runsPerEntry)

            // Story 2.9 wants pattern-engine signal on every entry — silently dropping entries
            // that produced no tags across all runs would let the gate pass on a subset of "easy"
            // corpus rows while harder entries quietly fail.
            val zeroTagEntries = perEntry.filter { it.emittedTags.isEmpty() }
            assertTrue(
                "STT-C failed: ${zeroTagEntries.size} entries produced zero tags across all " +
                    "$runsPerEntry runs (${zeroTagEntries.map(EntryStability::id)}). Pattern " +
                    "engine has no signal for these rows; tighten prompts or fix lens parsing.",
                zeroTagEntries.isEmpty(),
            )

            assertTrue(
                "STT-C failed: tag stability ${"%.2f".format(stability.rate)} < " +
                    "${"%.2f".format(STABILITY_THRESHOLD)} (stable ${stability.stablePairs} of " +
                    "${stability.totalPairs} observed (entry, tag) pairs). Inspect logcat tag '$TAG'.",
                stability.rate >= STABILITY_THRESHOLD,
            )
        }
    }

    private suspend fun runEntry(
        worker: BackgroundExtractionWorker,
        entry: CorpusEntry,
        runsPerEntry: Int,
    ): EntryStability {
        val perRun: List<RunOutcome> = (1..runsPerEntry).map { runIndex ->
            // Per-entry timeout backstop: a hung native call shouldn't drag the whole
            // 54-entry suite into the instrumentation-runner ceiling.
            val result = worker.extract(
                BackgroundExtractionRequest(
                    entryText = entry.entryText,
                    capturedAt = entry.capturedAt,
                    timeoutMs = PER_ENTRY_TIMEOUT_MS,
                ),
            )
            val tags = extractCanonicalTags(result)
            val parsed = parsedLensCount(result)
            android.util.Log.i(
                TAG,
                "entry=${entry.id} run=$runIndex/$runsPerEntry elapsed=${result.totalElapsedMs}ms " +
                    "lenses_parsed=$parsed/3 tags=${tags.joinToString(",")}",
            )
            RunOutcome(tags = tags, elapsedMs = result.totalElapsedMs, parsed = parsed)
        }

        val emittedTags: Set<String> = perRun.flatMap { it.tags }.toSet()
        val stableTags: Set<String> = emittedTags.filter { tag -> perRun.all { run -> tag in run.tags } }.toSet()
        return EntryStability(
            id = entry.id,
            runs = perRun,
            emittedTags = emittedTags,
            stableTags = stableTags,
        )
    }

    private fun extractCanonicalTags(result: BackgroundExtractionResult): Set<String> = when (result) {
        is BackgroundExtractionResult.Success -> tagsFromResolved(result.resolved)
        is BackgroundExtractionResult.Failed -> emptySet()
        is BackgroundExtractionResult.TimedOut -> emptySet()
    }

    private fun tagsFromResolved(resolved: ResolvedExtraction): Set<String> {
        // Per ADR-002 §"Convergence rules" the saved value is canonical (>=2 lenses agreed) or a
        // single Literal-strongest candidate; both flow through here. Ambiguous-tag entries
        // contribute the empty set on that run, which the stability ratio counts as a miss.
        val raw = resolved.fields["tags"]?.value ?: return emptySet()
        return (raw as? List<*>)?.mapNotNullTo(linkedSetOf()) { (it as? String)?.trim()?.lowercase() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
    }

    private fun parsedLensCount(result: BackgroundExtractionResult): Int =
        result.lensResults.count { it.extraction != null }

    private fun summarizeStability(perEntry: List<EntryStability>, runsPerEntry: Int): Stability {
        val totalPairs = perEntry.sumOf { it.emittedTags.size }
        val stablePairs = perEntry.sumOf { it.stableTags.size }
        val rate = if (totalPairs == 0) 0.0 else stablePairs.toDouble() / totalPairs.toDouble()
        return Stability(totalPairs = totalPairs, stablePairs = stablePairs, rate = rate, runsPerEntry = runsPerEntry)
    }

    private fun logSummary(perEntry: List<EntryStability>, stability: Stability, runsPerEntry: Int) {
        android.util.Log.i(
            TAG,
            "=== STT-C summary: stability=${"%.2f".format(stability.rate)} " +
                "(${stability.stablePairs}/${stability.totalPairs} pairs stable across $runsPerEntry runs) ===",
        )
        perEntry.forEach { entry ->
            val unstable = entry.emittedTags - entry.stableTags
            android.util.Log.i(
                TAG,
                "  ${entry.id}: stable=${entry.stableTags.size}/${entry.emittedTags.size} " +
                    "unstable_tags=$unstable",
            )
        }
    }

    private data class RunOutcome(val tags: Set<String>, val elapsedMs: Long, val parsed: Int)

    private data class EntryStability(
        val id: String,
        val runs: List<RunOutcome>,
        val emittedTags: Set<String>,
        val stableTags: Set<String>,
    )

    private data class Stability(val totalPairs: Int, val stablePairs: Int, val rate: Double, val runsPerEntry: Int)

    private companion object {
        const val TAG = "VestigeSttC"
        const val DEFAULT_RUNS_PER_ENTRY = 3
        const val MIN_RUNS_PER_ENTRY = 3
        const val STABILITY_THRESHOLD = 0.80
        const val PER_ENTRY_TIMEOUT_MS = 5 * 60_000L
    }
}
