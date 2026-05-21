package dev.anchildress1.vestige

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.vestige.debug.DebugPatternSeeder
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.VocabClustersCodec
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end on-device check that seeded PENDING rows actually run through the background worker
 * via pending-entry recovery.
 *
 * Requires the main model artifact already pushed (the typical `make reinstall` posture).
 * If the model is missing this test self-skips via `assumeTrue` — no spurious red.
 *
 * Slow: each row goes through the full 3-lens extraction; 35-row demo corpus can take
 * several minutes on GPU. Marked under the same wall-clock budget as the existing
 * single-entry smoke harness.
 */
@RunWith(AndroidJUnit4::class)
class MissingExtractionBackfillSmokeTest {

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        as VestigeApplication
    private val container get() = app.appContainer

    @Before
    fun seedFreshCorpus() {
        DebugPatternSeeder.seed(boxStore = container.boxStore)
    }

    @After
    fun resetOnboarding() {
        // Seeder leaves no onboarding artifacts behind, but the dev iteration loop sometimes
        // does — keep the device clean for subsequent suites.
    }

    @Test
    fun pending_recovery_lands_receipts_patterns_and_vectors_for_the_seed_corpus() = runBlocking {
        assumeTrue(
            "main model artifact missing — push via `make push-model` before running this smoke",
            mainModelArtifactPresent(),
        )

        val box = container.boxStore.boxFor(EntryEntity::class.java)
        val before = box.all
        assertTrue("seed corpus is empty — seeder broken", before.isNotEmpty())
        before.forEach { entry ->
            assertTrue(
                "row id=${entry.id} ships with non-empty receipts before backfill — seed contract violated",
                entry.lensReceiptsJson.isNullOrEmpty() || entry.lensReceiptsJson == "[]",
            )
        }

        container.recoverPendingExtractions()
        withTimeout(BACKFILL_TIMEOUT_MS) {
            // recoverPendingExtractions only *schedules* per-entry recovery jobs and returns; poll
            // until every row leaves the in-flight states (PENDING/RUNNING) so the COMPLETED
            // assertion below sees settled rows, not work still draining.
            while (box.all.any {
                    it.extractionStatus == ExtractionStatus.PENDING ||
                        it.extractionStatus == ExtractionStatus.RUNNING
                }
            ) {
                delay(POLL_INTERVAL_MS)
            }
        }

        val after = box.all.associateBy { it.id }
        val nonCompleted = after.values.filter { it.extractionStatus != ExtractionStatus.COMPLETED }
        val unfilled = after.values.filter { it.lensReceiptsJson.isNullOrEmpty() || it.lensReceiptsJson == "[]" }

        assertTrue(
            "backfill must finish every seeded row as COMPLETED; failures: " +
                nonCompleted.map { "${it.markdownFilename}:${it.extractionStatus}:${it.lastError}" },
            nonCompleted.isEmpty(),
        )
        assertTrue(
            "backfill left ${unfilled.size}/${after.size} rows without lens receipts: ${unfilled.map { it.id }}",
            unfilled.isEmpty(),
        )

        awaitVocabVectors()
        awaitSeedPatternsAreChallengeUsable()
    }

    private suspend fun awaitVocabVectors() {
        assumeTrue(
            "embedding artifacts missing — push optional EmbeddingGemma artifacts before running seed smoke",
            embeddingArtifactsPresent(),
        )
        container.launchVectorBackfillIfReady()
        val box = container.boxStore.boxFor(EntryEntity::class.java)
        withTimeout(VECTOR_BACKFILL_TIMEOUT_MS) {
            while (vocabEntriesMissingVectors(box.all).isNotEmpty()) {
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun awaitSeedPatternsAreChallengeUsable() {
        withTimeout(PATTERN_SETTLE_TIMEOUT_MS) {
            // Poll only on the tired vocab-drift pattern — that's the one that requires extraction
            // to land. Hard assertions (AUDIT-as-pattern leak, weak filler callouts) move OUTSIDE
            // the polling loop so they fail on the first probe instead of being re-raised every
            // POLL_INTERVAL_MS and burning the timeout window.
            while (!tiredVocabDriftPatternIsReady()) {
                delay(POLL_INTERVAL_MS)
            }
        }
        assertSeedPatternsAreChallengeUsable()
    }

    private fun tiredVocabDriftPatternIsReady(): Boolean {
        val patterns = container.boxStore.boxFor(PatternEntity::class.java).all
        val tired = patterns.firstOrNull {
            it.kind == PatternKind.VOCAB_FREQUENCY && it.signatureJson.contains(""""token":"tired"""")
        } ?: return false
        if (tired.supportingEntries.size < EXPECTED_VOCAB_ENTRY_COUNT) return false
        val clusters = VocabClustersCodec.decode(tired.vocabClustersJson)
        return clusters.size >= EXPECTED_VOCAB_CLUSTER_COUNT
    }

    private fun assertSeedPatternsAreChallengeUsable() {
        val patterns = container.boxStore.boxFor(PatternEntity::class.java).all
        val auditCatchAll = patterns.filter {
            it.kind == PatternKind.TEMPLATE_RECURRENCE && it.templateLabel == "audit"
        }
        assertTrue(
            "audit is fallback extraction metadata, not a demo pattern: ${auditCatchAll.map { it.title }}",
            auditCatchAll.isEmpty(),
        )

        val weakCallouts = patterns.filter {
            it.latestCalloutText.contains("Worth noting.", ignoreCase = true) ||
                it.latestCalloutText.contains("Same admin loop.", ignoreCase = true)
        }
        assertTrue(
            "pattern callouts must be evidence-specific, not filler: " +
                weakCallouts.map { "${it.title}: ${it.latestCalloutText}" },
            weakCallouts.isEmpty(),
        )
    }

    private fun mainModelArtifactPresent(): Boolean {
        // Mirror the AppContainer probe without forcing a coroutine: just check that the
        // production model dir holds a non-empty artifact file. False negatives here only
        // cause `assumeTrue` to skip — the production probe still gates real extraction.
        val modelDir = File(app.filesDir, MODEL_ARTIFACTS_SUBDIR)
        if (!modelDir.exists()) return false
        return modelDir.listFiles()?.any { it.isFile && it.length() > 0L } == true
    }

    private fun embeddingArtifactsPresent(): Boolean {
        val modelDir = File(app.filesDir, MODEL_ARTIFACTS_SUBDIR)
        if (!modelDir.exists()) return false
        val names = modelDir.listFiles()?.map { it.name }.orEmpty()
        return names.any { it.endsWith(".tflite") } && names.any { it == "sentencepiece.model" }
    }

    private fun vocabEntriesMissingVectors(entries: List<EntryEntity>): List<String> = entries
        .filter { it.vector == null || it.vectorSchemaVersion < EntryEntity.CURRENT_VECTOR_SCHEMA_VERSION }
        .map { it.markdownFilename }

    private companion object {
        // Backfill processes the seeded 35-row corpus serially through the 3-lens worker. The
        // reference device takes ~25-45s per row under GPU/runtime warmup, so this stays generous
        // enough to test completion instead of murdering an in-flight demo run.
        private const val BACKFILL_TIMEOUT_MS = 40L * 60 * 1000L
        private const val PATTERN_SETTLE_TIMEOUT_MS = 60_000L
        private const val VECTOR_BACKFILL_TIMEOUT_MS = 2L * 60 * 1000L
        private const val POLL_INTERVAL_MS = 1_000L
        private const val EXPECTED_VOCAB_ENTRY_COUNT = 23
        private const val EXPECTED_VOCAB_CLUSTER_COUNT = 3
        private const val MODEL_ARTIFACTS_SUBDIR = "models"
    }
}
