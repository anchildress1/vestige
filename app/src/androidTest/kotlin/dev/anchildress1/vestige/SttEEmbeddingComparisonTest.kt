package dev.anchildress1.vestige

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.vestige.corpus.SttEEntry
import dev.anchildress1.vestige.corpus.SttEManifest
import dev.anchildress1.vestige.inference.GemmaTextEmbedder
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.RetrievalRepo
import dev.anchildress1.vestige.storage.TagEntity
import dev.anchildress1.vestige.storage.VestigeBoxStore
import io.objectbox.BoxStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.time.Clock
import java.time.ZoneOffset

/**
 * STT-E gate: compares tag-only baseline vs embedding-augmented retrieval on the A/X fixture and
 * asserts the hybrid top-5 surfaces ≥4 aftermath entries. Runbook in
 * `docs/stt-e-manifest.example.txt`. Missing instrumentation args → [assumeTrue] skips so CI
 * without artifacts stays green.
 */
@RunWith(AndroidJUnit4::class)
class SttEEmbeddingComparisonTest {

    @Test
    fun hybridSurfacesMoreAftermathEntriesThanTagOnly() = runBlocking {
        val inputs = loadInputs()
        val corpus = loadCorpus(inputs.manifestFile)
        val seededRepo = openSeededRepo(corpus)
        try {
            val embedder = GemmaTextEmbedder(
                modelPath = inputs.modelFile.path,
                tokenizerPath = inputs.tokenizerFile.path,
            )
            assertHybridBeatsBaseline(seededRepo.repo, corpus, embedder)
        } finally {
            seededRepo.close()
        }
    }

    private fun seedCorpus(boxStore: BoxStore, corpus: List<SttEEntry>) {
        // io.objectbox.kotlin is implementation-scoped in :core-storage; use the Java accessor.
        val entryBox = boxStore.boxFor(EntryEntity::class.java)
        val tagBox = boxStore.boxFor(TagEntity::class.java)
        val tagEntities: Map<String, TagEntity> = corpus.flatMap { it.tags }.toSet().associateWith { name ->
            TagEntity(name = name, entryCount = 0).also { tagBox.put(it) }
        }
        corpus.forEach { source ->
            val entry = EntryEntity(
                entryText = source.entryText,
                timestampEpochMs = source.capturedAt.toInstant().toEpochMilli(),
                markdownFilename = "${source.id}.md",
            )
            entryBox.put(entry)
            entry.tags.addAll(source.tags.map { tagEntities.getValue(it) })
            entryBox.put(entry)
        }
    }

    private fun logRanking(label: String, ranked: List<EntryEntity>, corpus: List<SttEEntry>) {
        android.util.Log.i(TAG, "--- $label top-${ranked.size} ---")
        ranked.forEachIndexed { index, entry ->
            val id = entryIdToCorpusId(entry, corpus)
            val cohort = if (id in AFTERMATH_IDS) "A" else "X"
            android.util.Log.i(
                TAG,
                "  ${index + 1}. [$cohort] $id — ${entry.entryText.take(SNIPPET_CHARS)}",
            )
        }
    }

    private fun entryIdToCorpusId(entry: EntryEntity, corpus: List<SttEEntry>): String =
        corpus.firstOrNull { it.entryText == entry.entryText }?.id ?: "?"

    private fun loadInputs(): SttEInputs {
        val args = InstrumentationRegistry.getArguments()
        val modelPath = args.getString("embeddingModelPath")
        val tokenizerPath = args.getString("embeddingTokenizerPath")
        val manifestPath = args.getString("manifestPath")
        assumeTrue("embeddingModelPath instrumentation argument not provided", modelPath != null)
        assumeTrue("embeddingTokenizerPath instrumentation argument not provided", tokenizerPath != null)
        assumeTrue("manifestPath instrumentation argument not provided", manifestPath != null)

        val modelFile = File(modelPath!!)
        val tokenizerFile = File(tokenizerPath!!)
        val manifestFile = File(manifestPath!!)
        assumeTrue("Model file not found at $modelPath", modelFile.exists() && modelFile.canRead())
        assumeTrue(
            "Tokenizer file not found at $tokenizerPath",
            tokenizerFile.exists() && tokenizerFile.canRead(),
        )
        assumeTrue("Manifest not found at $manifestPath", manifestFile.exists() && manifestFile.canRead())
        return SttEInputs(modelFile = modelFile, tokenizerFile = tokenizerFile, manifestFile = manifestFile)
    }

    private fun loadCorpus(manifestFile: File): List<SttEEntry> {
        val corpus = SttEManifest.load(manifestFile)
        require(corpus.map(SttEEntry::id).toSet() == EXPECTED_IDS) {
            "STT-E manifest must contain exactly $EXPECTED_IDS — got ${corpus.map(SttEEntry::id)}"
        }
        return corpus
    }

    private fun openSeededRepo(corpus: List<SttEEntry>): SeededRepo {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dataDir = File(context.cacheDir, "objectbox-stt-e-${System.nanoTime()}")
        val boxStore = VestigeBoxStore.openAt(dataDir)
        seedCorpus(boxStore, corpus)

        // Pin clock to the most recent fixture timestamp so the 90-day recency window includes
        // every seeded entry deterministically — same convention as RetrievalRepoTest fixtures.
        val newest = corpus.maxOf { it.capturedAt.toInstant() }
        val repo = RetrievalRepo(boxStore, Clock.fixed(newest, ZoneOffset.UTC))
        return SeededRepo(boxStore = boxStore, dataDir = dataDir, repo = repo)
    }

    private suspend fun assertHybridBeatsBaseline(
        repo: RetrievalRepo,
        corpus: List<SttEEntry>,
        embedder: GemmaTextEmbedder,
    ) {
        val baseline = repo.query(QUERY, topN = TOP_N)
        val hybrid = repo.queryHybrid(QUERY, { embedder.embed(it) }, topN = TOP_N)

        logRanking("baseline (keyword+tag+recency)", baseline, corpus)
        logRanking("hybrid (+ EmbeddingGemma cosine)", hybrid, corpus)

        val baselineIds = baseline.map { entryIdToCorpusId(it, corpus) }
        val hybridIds = hybrid.map { entryIdToCorpusId(it, corpus) }
        val baselineStats = summarizeRanking(baselineIds)
        val hybridStats = summarizeRanking(hybridIds)

        logCoverage("baseline", baselineStats)
        logCoverage("hybrid", hybridStats)
        assertHybridCoverage(hybridIds, hybridStats)
        assertTrue(
            "STT-E failed: hybrid did not visibly outperform baseline. Baseline=$baselineIds " +
                "(relevant=${baselineStats.relevantCount}, distractorsBefore4th=" +
                "${baselineStats.distractorsBeforeFourthRelevant}), hybrid=$hybridIds " +
                "(relevant=${hybridStats.relevantCount}, distractorsBefore4th=" +
                "${hybridStats.distractorsBeforeFourthRelevant}).",
            hybridStats.relevantCount > baselineStats.relevantCount ||
                (
                    hybridStats.relevantCount == baselineStats.relevantCount &&
                        hybridStats.distractorsBeforeFourthRelevant <
                        baselineStats.distractorsBeforeFourthRelevant
                    ),
        )
    }

    private fun logCoverage(label: String, stats: RankingStats) {
        android.util.Log.i(
            TAG,
            "=== STT-E $label coverage: ${stats.relevantCount}/${AFTERMATH_IDS.size} " +
                "A-entries in top $TOP_N; distractors before 4th relevant = " +
                "${stats.distractorsBeforeFourthRelevant} ===",
        )
    }

    private fun assertHybridCoverage(hybridIds: List<String>, hybridStats: RankingStats) {
        assertTrue(
            "STT-E failed: hybrid surfaced only ${hybridStats.relevantCount}/${AFTERMATH_IDS.size} " +
                "A-entries in top $TOP_N ($hybridIds). Threshold = $MIN_AFTERMATH_IN_TOP_N. " +
                "Inspect logcat tag '$TAG' for the per-entry ranking + score breakdown.",
            hybridStats.relevantCount >= MIN_AFTERMATH_IN_TOP_N,
        )
        assertTrue(
            "STT-E failed: hybrid ranked ${hybridStats.distractorsBeforeFourthRelevant} distractor(s) " +
                "before the fourth relevant entry ($hybridIds). Max allowed = " +
                "$MAX_DISTRACTORS_BEFORE_FOURTH_RELEVANT.",
            hybridStats.distractorsBeforeFourthRelevant <= MAX_DISTRACTORS_BEFORE_FOURTH_RELEVANT,
        )
    }

    private fun summarizeRanking(ids: List<String>): RankingStats {
        var relevantCount = 0
        var distractorsBeforeFourthRelevant = 0
        for (id in ids) {
            when {
                id in AFTERMATH_IDS -> {
                    relevantCount += 1
                    if (relevantCount == MIN_AFTERMATH_IN_TOP_N) break
                }

                id in DISTRACTOR_IDS -> distractorsBeforeFourthRelevant += 1
            }
        }
        return RankingStats(
            relevantCount = relevantCount,
            distractorsBeforeFourthRelevant = distractorsBeforeFourthRelevant,
        )
    }

    private data class SttEInputs(val modelFile: File, val tokenizerFile: File, val manifestFile: File)

    private class SeededRepo(private val boxStore: BoxStore, private val dataDir: File, val repo: RetrievalRepo) :
        Closeable {
        override fun close() {
            boxStore.close()
            BoxStore.deleteAllFiles(dataDir)
        }
    }

    private data class RankingStats(val relevantCount: Int, val distractorsBeforeFourthRelevant: Int)

    private companion object {
        const val TAG = "VestigeSttE"
        const val QUERY = "Show entries like the post-meeting crash even when I used different words."
        const val TOP_N = 5
        const val MIN_AFTERMATH_IN_TOP_N = 4
        const val MAX_DISTRACTORS_BEFORE_FOURTH_RELEVANT = 1
        const val SNIPPET_CHARS = 80
        val AFTERMATH_IDS = setOf("A1", "A2", "A3", "A4", "A5", "A6")
        val DISTRACTOR_IDS = setOf("X1", "X2", "X3")
        val EXPECTED_IDS = AFTERMATH_IDS + DISTRACTOR_IDS
    }
}
