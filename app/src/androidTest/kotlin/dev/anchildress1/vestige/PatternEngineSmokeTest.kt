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
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.storage.MarkdownEntryStore
import dev.anchildress1.vestige.storage.PatternDetector
import dev.anchildress1.vestige.storage.VestigeBoxStore
import io.objectbox.BoxStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 3 §Story 3.5 smoke test — runs the real model over the on-device corpus, persists each
 * entry through the real `EntryStore`, then asks `PatternDetector` for cross-entry patterns.
 *
 * Manual, on-device, network-sealed. The corpus default is the small Phase 3 subset (A1-A3 +
 * D1-D3) which guarantees the `TIME_OF_DAY_CLUSTER` threshold from the three 2am-4am entries
 * regardless of how the model labels A1-A3; if the model also lands a consistent
 * `templateLabel` on the aftermath subset, `TEMPLATE_RECURRENCE` lights up too. Either way
 * the test passes when ≥1 cross-entry pattern surfaces.
 *
 *   adb push gemma-4-E4B-it.litertlm /data/local/tmp/
 *   adb push docs/stt-c-manifest.example.txt /data/local/tmp/stt-c-manifest.txt
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -PmodelPath=/data/local/tmp/gemma-4-E4B-it.litertlm \
 *     -PmanifestPath=/data/local/tmp/stt-c-manifest.txt \
 *     -Pandroid.testInstrumentationRunnerArguments.class=dev.anchildress1.vestige.PatternEngineSmokeTest
 *
 * Missing args → `assumeTrue` skips so CI without artifacts stays green.
 */
@RunWith(AndroidJUnit4::class)
class PatternEngineSmokeTest {

    @Test
    fun atLeastOnePatternSurfacesAfterCorpusPersist() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val modelPath = args.getString("modelPath")
        val manifestPath = args.getString("manifestPath")
        assumeTrue("modelPath instrumentation argument not provided", modelPath != null)
        assumeTrue("manifestPath instrumentation argument not provided", manifestPath != null)
        val modelFile = File(modelPath!!)
        val manifestFile = File(manifestPath!!)
        assumeTrue("Model file not found at $modelPath", modelFile.exists() && modelFile.canRead())
        assumeTrue("Manifest not found at $manifestPath", manifestFile.exists() && manifestFile.canRead())

        val fullCorpus = CorpusManifest.load(manifestFile)
        val corpus = fullCorpus.filter { it.id in SUBSET_IDS }
        assumeTrue(
            "Manifest at $manifestPath is missing one or more required ids ($SUBSET_IDS) for the " +
                "Phase 3 smoke subset; loaded ${corpus.map(CorpusEntry::id)}",
            corpus.size == SUBSET_IDS.size,
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val boxStoreDir = File(context.filesDir, "objectbox-smoke-${System.nanoTime()}")
        val markdownDir = File(context.filesDir, "markdown-smoke-${System.nanoTime()}")
        val boxStore: BoxStore = VestigeBoxStore.openAt(boxStoreDir)
        val entryStore = EntryStore(boxStore, MarkdownEntryStore(markdownDir))
        val backend = InferenceBackendArg.resolve(args)
        android.util.Log.i(TAG, "Phase 3 smoke using backend=$backend, ${corpus.size} entries")

        try {
            LiteRtLmEngine(
                modelPath = modelPath,
                backend = backend,
                cacheDir = context.cacheDir.absolutePath,
            ).use { engine ->
                engine.initialize()
                val worker = BackgroundExtractionWorker(engine = engine, resolver = DefaultConvergenceResolver())
                for (entry in corpus) {
                    persistEntry(entryStore, worker, entry)
                }

                val detector = PatternDetector(boxStore)
                val patterns = detector.detect()
                android.util.Log.i(
                    TAG,
                    "Detected ${patterns.size} pattern(s) over ${corpus.size} entries: " +
                        patterns.joinToString { "${it.kind.serial}(${it.supportingEntryCount})" },
                )
                assertTrue(
                    "Phase 3 smoke failed: expected ≥1 cross-entry pattern over ${corpus.size} " +
                        "corpus entries, got ${patterns.size}. Inspect logcat tag '$TAG'.",
                    patterns.isNotEmpty(),
                )
            }
        } finally {
            boxStore.close()
            BoxStore.deleteAllFiles(boxStoreDir)
            markdownDir.deleteRecursively()
        }
    }

    private suspend fun persistEntry(entryStore: EntryStore, worker: BackgroundExtractionWorker, entry: CorpusEntry) {
        val entryId = entryStore.createPendingEntry(entry.entryText, entry.capturedAt.toInstant())
        val result = worker.extract(
            BackgroundExtractionRequest(
                entryText = entry.entryText,
                capturedAt = entry.capturedAt,
                timeoutMs = PER_ENTRY_TIMEOUT_MS,
            ),
        )
        when (result) {
            is BackgroundExtractionResult.Success -> {
                entryStore.completeEntry(entryId, result.resolved, result.templateLabel)
                val label = result.templateLabel?.serial ?: "null"
                android.util.Log.i(TAG, "entry=${entry.id} template=$label elapsed=${result.totalElapsedMs}ms")
            }

            else -> android.util.Log.w(TAG, "entry=${entry.id} non-success: ${result.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "VestigeP3Smoke"
        private const val PER_ENTRY_TIMEOUT_MS = 180_000L

        // A1-A3 cover aftermath candidates; D1-D3 are guaranteed-goblin entries (timestamp-only
        // detection, no model agreement required). At least the goblin TIME_OF_DAY_CLUSTER
        // pattern fires by construction.
        private val SUBSET_IDS = setOf("A1", "A2", "A3", "D1", "D2", "D3")
    }
}
