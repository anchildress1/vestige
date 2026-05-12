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
 * STT-E gate: runs the four scenario queries (aftermath / invoice / decision-spiral / late-night)
 * through both retrieval paths against the 18-entry fixture, scores per-query wins, and asserts
 * hybrid wins on ≥50% of queries. Per-query "win" = hybrid surfaces more cohort-relevant entries
 * in the top-N than baseline (ties don't count as wins).
 *
 * Runbook in `docs/stt-e-manifest.example.txt`. Missing instrumentation args → [assumeTrue]
 * skips so CI without artifacts stays green.
 */
@RunWith(AndroidJUnit4::class)
class SttEEmbeddingComparisonTest {

    @Test
    fun hybridSurfacesMoreCohortEntriesThanTagOnly() = runBlocking {
        val inputs = loadInputs()
        val corpus = loadCorpus(inputs.manifestFile)
        val seededRepo = openSeededRepo(corpus)
        try {
            val rawEmbedder = GemmaTextEmbedder(
                modelPath = inputs.modelFile.path,
                tokenizerPath = inputs.tokenizerFile.path,
            )
            // Memoize so each unique text is embedded once across all queries — 18 entries +
            // 4 query strings instead of 4 × (18 entries + 1 query) per-call hits.
            val cache = mutableMapOf<String, FloatArray>()
            val embedder: suspend (String) -> FloatArray = { text ->
                cache.getOrPut(text) { rawEmbedder.embed(text) }
            }
            val scoreboard = runQueries(seededRepo.repo, corpus, embedder)
            logScoreboard(scoreboard)
            assertWinRate(scoreboard)
        } finally {
            seededRepo.close()
        }
    }

    private suspend fun runQueries(
        repo: RetrievalRepo,
        corpus: List<SttEEntry>,
        embedder: suspend (String) -> FloatArray,
    ): List<QueryOutcome> = QUERIES.map { query ->
        val baseline = repo.query(query.text, topN = TOP_N)
        val hybrid = repo.queryHybrid(query.text, embedder, topN = TOP_N)
        val baselineIds = baseline.map { entryIdToCorpusId(it, corpus) }
        val hybridIds = hybrid.map { entryIdToCorpusId(it, corpus) }
        val baselineRelevant = baselineIds.count { it in query.relevantIds }
        val hybridRelevant = hybridIds.count { it in query.relevantIds }
        logRanking(query, "baseline", baselineIds)
        logRanking(query, "hybrid", hybridIds)
        QueryOutcome(
            query = query,
            baselineIds = baselineIds,
            hybridIds = hybridIds,
            baselineRelevant = baselineRelevant,
            hybridRelevant = hybridRelevant,
        )
    }

    private fun logRanking(query: SttEQuery, label: String, ids: List<String>) {
        val annotated = ids.joinToString(", ") { id ->
            val cohort = if (id in query.relevantIds) "✓$id" else id
            cohort
        }
        android.util.Log.i(TAG, "${query.id} $label top-${ids.size}: $annotated")
    }

    private fun logScoreboard(scoreboard: List<QueryOutcome>) {
        android.util.Log.i(TAG, "=== STT-E scoreboard (relevant in top-$TOP_N) ===")
        scoreboard.forEach { outcome ->
            val verdict = when {
                outcome.hybridRelevant > outcome.baselineRelevant -> "HYBRID WIN"
                outcome.hybridRelevant < outcome.baselineRelevant -> "BASELINE WIN"
                else -> "TIE"
            }
            android.util.Log.i(
                TAG,
                "  ${outcome.query.id}: baseline=${outcome.baselineRelevant}/${outcome.query.relevantIds.size} " +
                    "hybrid=${outcome.hybridRelevant}/${outcome.query.relevantIds.size} — $verdict",
            )
        }
        val wins = scoreboard.count { it.hybridRelevant > it.baselineRelevant }
        val losses = scoreboard.count { it.hybridRelevant < it.baselineRelevant }
        val ties = scoreboard.count { it.hybridRelevant == it.baselineRelevant }
        android.util.Log.i(
            TAG,
            "=== aggregate: hybrid wins=$wins losses=$losses ties=$ties of ${scoreboard.size} queries ===",
        )
    }

    private fun assertWinRate(scoreboard: List<QueryOutcome>) {
        val wins = scoreboard.count { it.hybridRelevant > it.baselineRelevant }
        val required = (scoreboard.size + 1) / 2 // ceil(half) — 4 queries → 2 wins; 5 → 3
        assertTrue(
            "STT-E failed: hybrid won only $wins of ${scoreboard.size} queries (need ≥$required). " +
                "Per-query relevant counts: " +
                scoreboard.joinToString("; ") {
                    "${it.query.id} base=${it.baselineRelevant} hyb=${it.hybridRelevant}"
                } + ". Inspect logcat tag '$TAG' for full top-$TOP_N listings.",
            wins >= required,
        )
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

    private data class SttEInputs(val modelFile: File, val tokenizerFile: File, val manifestFile: File)

    private class SeededRepo(private val boxStore: BoxStore, private val dataDir: File, val repo: RetrievalRepo) :
        Closeable {
        override fun close() {
            boxStore.close()
            BoxStore.deleteAllFiles(dataDir)
        }
    }

    private data class SttEQuery(val id: String, val text: String, val relevantIds: Set<String>)

    private data class QueryOutcome(
        val query: SttEQuery,
        val baselineIds: List<String>,
        val hybridIds: List<String>,
        val baselineRelevant: Int,
        val hybridRelevant: Int,
    )

    private companion object {
        const val TAG = "VestigeSttE"
        const val TOP_N = 5

        val AFTERMATH_IDS = setOf("A1", "A2", "A3", "A4", "A5", "A6")
        val INVOICE_IDS = setOf("B1", "B2", "B3")
        val DECISION_IDS = setOf("C1", "C2", "C3")
        val LATE_NIGHT_IDS = setOf("D1", "D2", "D3")
        val DISTRACTOR_IDS = setOf("X1", "X2", "X3")
        val EXPECTED_IDS = AFTERMATH_IDS + INVOICE_IDS + DECISION_IDS + LATE_NIGHT_IDS + DISTRACTOR_IDS

        val QUERIES = listOf(
            SttEQuery(
                id = "Q_aftermath",
                text = "Show entries like the post-meeting crash even when I used different words.",
                relevantIds = AFTERMATH_IDS,
            ),
            SttEQuery(
                id = "Q_invoice",
                text = "Find times I avoided sending the invoice.",
                relevantIds = INVOICE_IDS,
            ),
            SttEQuery(
                id = "Q_decision",
                text = "When have I gotten stuck choosing between options?",
                relevantIds = DECISION_IDS,
            ),
            SttEQuery(
                id = "Q_lateNight",
                text = "Show times I rearranged things at 3am instead of sleeping.",
                relevantIds = LATE_NIGHT_IDS,
            ),
        )
    }
}
