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
 * End-to-end STT-E gate — seeds the 18-entry corpus with real EmbeddingGemma vectors, compares
 * tag-only baseline vs hybrid retrieval across four scenario queries, and requires the hybrid path
 * to win at least half of them while still clearing a minimum relevance floor. Captures the full
 * retrieval stack on the reference S24 Ultra and guards against regressions in the schema +
 * scorer + embedder wiring. Runbook in `docs/stt-e-manifest.example.txt`. Missing
 * instrumentation args → [assumeTrue] skips so CI without artifacts stays green.
 */
@RunWith(AndroidJUnit4::class)
class SttEEmbeddingComparisonTest {

    @Test
    fun hybridRetrieval_beatsTagOnlyBaselineAcrossTheCorpus() = runBlocking {
        val inputs = loadInputs()
        val corpus = loadCorpus(inputs.manifestFile)
        val rawEmbedder = GemmaTextEmbedder(
            modelPath = inputs.modelFile.path,
            tokenizerPath = inputs.tokenizerFile.path,
        )
        // Memoize so each unique text is embedded once across seeding + queries.
        val cache = mutableMapOf<String, FloatArray>()
        val embedder: suspend (String) -> FloatArray = { text ->
            cache.getOrPut(text) { rawEmbedder.embed(text) }
        }

        val seeded = openSeededRepo(corpus, embedder)
        try {
            val comparisons = QUERIES.map { query -> compareAgainstBaseline(seeded.repo, corpus, query) }
            val wins = comparisons.count { it.isHybridWin }
            android.util.Log.i(TAG, "STT-E win-rate: $wins/${QUERIES.size} queries")
            assertTrue(
                "STT-E failed: hybrid won only $wins/${QUERIES.size} queries. " +
                    comparisons.joinToString(prefix = "Comparisons=[", postfix = "]") { it.summary() },
                wins >= MIN_QUERY_WINS,
            )
        } finally {
            seeded.close()
        }
    }

    private suspend fun seedCorpus(
        boxStore: BoxStore,
        corpus: List<SttEEntry>,
        embedder: suspend (String) -> FloatArray,
    ) {
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
                vector = embedder(source.entryText),
            )
            entryBox.put(entry)
            entry.tags.addAll(source.tags.map { tagEntities.getValue(it) })
            entryBox.put(entry)
        }
    }

    private fun entryIdToCorpusId(entry: EntryEntity, corpus: List<SttEEntry>): String =
        corpus.firstOrNull { it.entryText == entry.entryText }?.id ?: "?"

    private suspend fun compareAgainstBaseline(
        repo: RetrievalRepo,
        corpus: List<SttEEntry>,
        query: SttEQuery,
    ): QueryComparison {
        val baseline = repo.query(query.text, topN = TOP_N, embeddingWeight = 0f)
        val hybrid = repo.query(query.text, topN = TOP_N)
        val baselineIds = baseline.map { entryIdToCorpusId(it, corpus) }
        val hybridIds = hybrid.map { entryIdToCorpusId(it, corpus) }
        val baselineRelevantCount = baselineIds.count { it in query.relevantIds }
        val hybridRelevantCount = hybridIds.count { it in query.relevantIds }
        val hybridNovelRelevantIds = hybridIds.filterTo(linkedSetOf()) { id ->
            id in query.relevantIds && id !in baselineIds
        }
        android.util.Log.i(
            TAG,
            "${query.id} baseline top-$TOP_N: $baselineIds (relevant=$baselineRelevantCount/${query.relevantIds.size})",
        )
        android.util.Log.i(
            TAG,
            "${query.id} hybrid top-$TOP_N: $hybridIds (relevant=$hybridRelevantCount/${query.relevantIds.size}, " +
                "novelRelevant=$hybridNovelRelevantIds)",
        )
        assertTrue(
            "${query.id} surfaced $hybridRelevantCount relevant entries in top $TOP_N; need ≥$MIN_RELEVANT. " +
                "Got $hybridIds.",
            hybridRelevantCount >= MIN_RELEVANT,
        )
        return QueryComparison(
            queryId = query.id,
            baselineIds = baselineIds,
            hybridIds = hybridIds,
            baselineRelevantCount = baselineRelevantCount,
            hybridRelevantCount = hybridRelevantCount,
            hybridNovelRelevantIds = hybridNovelRelevantIds,
        )
    }

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

    private suspend fun openSeededRepo(corpus: List<SttEEntry>, embedder: suspend (String) -> FloatArray): SeededRepo {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dataDir = File(context.cacheDir, "objectbox-stt-e-${System.nanoTime()}")
        val boxStore = VestigeBoxStore.openAt(dataDir)
        seedCorpus(boxStore, corpus, embedder)

        // Pin clock to the most recent fixture timestamp so the 90-day recency window includes
        // every seeded entry deterministically — same convention as RetrievalRepoTest fixtures.
        val newest = corpus.maxOf { it.capturedAt.toInstant() }
        val repo = RetrievalRepo(boxStore, embedder, Clock.fixed(newest, ZoneOffset.UTC))
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

    private data class QueryComparison(
        val queryId: String,
        val baselineIds: List<String>,
        val hybridIds: List<String>,
        val baselineRelevantCount: Int,
        val hybridRelevantCount: Int,
        val hybridNovelRelevantIds: Set<String>,
    ) {
        val isHybridWin: Boolean
            get() = hybridRelevantCount >= baselineRelevantCount && hybridNovelRelevantIds.isNotEmpty()

        fun summary(): String = "$queryId{baseline=$baselineIds,relevant=$baselineRelevantCount," +
            "hybrid=$hybridIds,hybridRelevant=$hybridRelevantCount,novel=$hybridNovelRelevantIds}"
    }

    private companion object {
        const val TAG = "VestigeSttE"
        const val TOP_N = 5

        // Per-query relevance floor — A cohort has 6 entries (room for 4/5); B/C/D have 3 (most
        // a query can surface in top-5 is 3). 2/5 is a real-but-imperfect retrieval signal.
        const val MIN_RELEVANT = 2
        const val MIN_QUERY_WINS = 2

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
