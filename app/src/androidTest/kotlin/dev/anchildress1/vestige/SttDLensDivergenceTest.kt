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
import dev.anchildress1.vestige.inference.LensResult
import dev.anchildress1.vestige.inference.LiteRtLmEngine
import dev.anchildress1.vestige.inference.SkepticalFlagKinds
import dev.anchildress1.vestige.model.Lens
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * STT-D — lens-divergence verification per `docs/stories/phase-2-core-loop.md` §Story 2.7 and
 * `docs/sample-data-scenarios.md` §STT-D. Runs the manifest entries through
 * [BackgroundExtractionWorker], records per-lens output, and asserts at least
 * [StopAndTestCorpusRules.requiredDivergentEntries] entries (30%) show meaningful field-level
 * divergence — Skeptical flags, lens disagreement on a non-empty value, or Literal refusing an
 * inference that Inferential populated.
 *
 * Manual, on-device, network-sealed. Push artifacts then run:
 *
 *   adb push gemma-4-E4B-it.litertlm /data/local/tmp/
 *   adb push docs/stt-d-manifest.example.txt /data/local/tmp/stt-d-manifest.txt
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -PmodelPath=/data/local/tmp/gemma-4-E4B-it.litertlm \
 *     -PmanifestPath=/data/local/tmp/stt-d-manifest.txt \
 *     -Pandroid.testInstrumentationRunnerArguments.class=dev.anchildress1.vestige.SttDLensDivergenceTest
 *
 * Missing args → [assumeTrue] skips so CI without artifacts stays green.
 */
@RunWith(AndroidJUnit4::class)
class SttDLensDivergenceTest {

    @Test
    fun threeLensesDivergeOnAtLeastTwoOfSixEntries() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val modelPath = args.getString("modelPath")
        val manifestPath = args.getString("manifestPath")
        assumeTrue("modelPath instrumentation argument not provided", modelPath != null)
        assumeTrue("manifestPath instrumentation argument not provided", manifestPath != null)
        val modelFile = File(modelPath!!)
        val manifestFile = File(manifestPath!!)
        assumeTrue("Model file not found at $modelPath", modelFile.exists() && modelFile.canRead())
        assumeTrue("Manifest not found at $manifestPath", manifestFile.exists() && manifestFile.canRead())

        val corpus = CorpusManifest.load(manifestFile)
        StopAndTestCorpusRules.requireCanonicalSttDCorpus(corpus.map(CorpusEntry::id))
        val requiredDivergentEntries = StopAndTestCorpusRules.requiredDivergentEntries(corpus.size)

        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val backend = InferenceBackendArg.resolve(args)
        android.util.Log.i(TAG, "STT-D using backend=$backend")
        val engine = LiteRtLmEngine(
            modelPath = modelPath,
            backend = backend,
            cacheDir = cacheDir.absolutePath,
        )

        engine.use {
            it.initialize()
            val worker = BackgroundExtractionWorker(engine = it, resolver = DefaultConvergenceResolver())

            val reports = corpus.map { entry -> runEntry(worker, entry) }
            logSummary(reports)

            val divergent = reports.count { it.isMeaningful }
            assertTrue(
                "STT-D failed: only $divergent/${reports.size} entries showed meaningful lens " +
                    "divergence (need >= $requiredDivergentEntries). Inspect logcat tag '$TAG'.",
                divergent >= requiredDivergentEntries,
            )
        }
    }

    private suspend fun runEntry(worker: BackgroundExtractionWorker, entry: CorpusEntry): EntryReport {
        val result = worker.extract(
            BackgroundExtractionRequest(
                entryText = entry.entryText,
                capturedAt = entry.capturedAt,
                timeoutMs = PER_ENTRY_TIMEOUT_MS,
            ),
        )
        val lensResults = result.lensResults.associateBy { it.lens }
        val divergence = analyzeDivergence(lensResults)
        logEntry(entry, result, divergence)
        return EntryReport(
            id = entry.id,
            divergentFields = divergence.fieldNames,
            skepticalFlags = divergence.skepticalFlags,
            inferentialOnly = divergence.inferentialOnly,
            isMeaningful = divergence.isMeaningful,
            elapsedMs = result.totalElapsedMs,
        )
    }

    private fun analyzeDivergence(lensResults: Map<Lens, LensResult>): Divergence {
        val literal = lensResults[Lens.LITERAL]?.extraction
        val inferential = lensResults[Lens.INFERENTIAL]?.extraction
        val skeptical = lensResults[Lens.SKEPTICAL]?.extraction
        val skepticalFlags: List<String> = skeptical?.flags
            .orEmpty()
            .filter(SkepticalFlagKinds::isSchemaBinding)

        val disagreementFields = COMPARABLE_FIELDS.filter { key ->
            val values = listOfNotNull(literal, inferential, skeptical).mapNotNull { extraction ->
                extraction.fields[key]?.takeIf(::isMeaningfulValue)?.let { canonicalize(it) }
            }
            values.toSet().size >= MIN_DISTINCT_FOR_DISAGREEMENT
        }
        // null literal means the lens call failed — skip the inferentialOnly channel.
        val inferentialOnly = if (literal == null) {
            emptyList()
        } else {
            COMPARABLE_FIELDS.filter { key ->
                isMeaningfulValue(inferential?.fields?.get(key)) && !isMeaningfulValue(literal.fields[key])
            }
        }

        return Divergence(
            fieldNames = disagreementFields,
            skepticalFlags = skepticalFlags,
            inferentialOnly = inferentialOnly,
        )
    }

    private fun isMeaningfulValue(value: Any?): Boolean = when (value) {
        null -> false
        is String -> value.isNotBlank()
        is List<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> true
    }

    /** Canonical form for equality across lenses — case/whitespace shouldn't count as divergence. */
    private fun canonicalize(value: Any): Any = when (value) {
        is String -> value.trim().lowercase()
        is List<*> -> value.mapNotNull { canonicalizeMember(it) }.toSet()
        is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to (v?.let(::canonicalize) ?: "") }
        else -> value
    }

    private fun canonicalizeMember(value: Any?): Any? = value?.let(::canonicalize)

    private fun logEntry(entry: CorpusEntry, result: BackgroundExtractionResult, divergence: Divergence) {
        android.util.Log.i(
            TAG,
            "entry=${entry.id} elapsed=${result.totalElapsedMs}ms " +
                "lenses_parsed=${result.lensResults.count { it.extraction != null }}/3 " +
                "model_calls=${result.modelCallCount}",
        )
        Lens.entries.forEach { lens ->
            val lensResult = result.lensResults.firstOrNull { it.lens == lens }
            val extraction = lensResult?.extraction
            val tags = (extraction?.fields?.get("tags") as? List<*>)?.joinToString(",") ?: "<no-parse>"
            val energy = extraction?.fields?.get("energy_descriptor") as? String ?: "null"
            val commitment = extraction?.fields?.get("stated_commitment")?.let { "present" } ?: "null"
            val flagCount = extraction?.flags?.size ?: 0
            android.util.Log.i(
                TAG,
                "  $lens tags=[$tags] energy=$energy commitment=$commitment flags=$flagCount " +
                    "attempts=${lensResult?.attemptCount ?: 0} err=${lensResult?.lastError ?: "-"}",
            )
        }
        android.util.Log.i(
            TAG,
            "  -> disagree_fields=${divergence.fieldNames} inferential_only=${divergence.inferentialOnly} " +
                "skeptical_flags=${divergence.skepticalFlags.size} meaningful=${divergence.isMeaningful}",
        )
    }

    private fun logSummary(reports: List<EntryReport>) {
        val divergent = reports.count { it.isMeaningful }
        android.util.Log.i(TAG, "=== STT-D summary: $divergent/${reports.size} divergent ===")
        reports.forEach { report ->
            android.util.Log.i(
                TAG,
                "  ${report.id}: meaningful=${report.isMeaningful} fields=${report.divergentFields} " +
                    "skeptical_flags=${report.skepticalFlags.size} inferential_only=${report.inferentialOnly} " +
                    "elapsed=${report.elapsedMs}ms",
            )
        }
    }

    private data class Divergence(
        val fieldNames: List<String>,
        val skepticalFlags: List<String>,
        val inferentialOnly: List<String>,
    ) {
        val isMeaningful: Boolean
            get() = fieldNames.isNotEmpty() || skepticalFlags.isNotEmpty() || inferentialOnly.isNotEmpty()
    }

    private data class EntryReport(
        val id: String,
        val divergentFields: List<String>,
        val skepticalFlags: List<String>,
        val inferentialOnly: List<String>,
        val isMeaningful: Boolean,
        val elapsedMs: Long,
    )

    private companion object {
        const val TAG = "VestigeSttD"
        const val MIN_DISTINCT_FOR_DISAGREEMENT = 2
        const val PER_ENTRY_TIMEOUT_MS = 5 * 60_000L

        /** Schema fields the STT-D divergence verdict compares across lenses. */
        val COMPARABLE_FIELDS: List<String> = listOf(
            "tags",
            "energy_descriptor",
            "state_shift",
            "stated_commitment",
            "recurrence_link",
            "recurrence_kind",
            "vocabulary_contradictions",
        )
    }
}
