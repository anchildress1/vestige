package dev.anchildress1.vestige

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.vestige.debug.DebugPatternSeeder
import dev.anchildress1.vestige.storage.EntryEntity
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
 * End-to-end on-device check that `launchMissingExtractionBackfill()` actually fills in
 * the lens receipts on every seeded row that ships under the COMPLETED-with-empty-receipts
 * seed contract, and that each successful completion bumps the row's `attemptCount`.
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
        DebugPatternSeeder.seed(
            filesDir = app.filesDir,
            boxStore = container.boxStore,
            patternStore = container.patternStore,
        )
    }

    @After
    fun resetOnboarding() {
        // Seeder leaves no onboarding artifacts behind, but the dev iteration loop sometimes
        // does — keep the device clean for subsequent suites.
    }

    @Test
    fun backfill_lands_receipts_and_bumps_attempt_count_on_every_row() = runBlocking {
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

        withTimeout(BACKFILL_TIMEOUT_MS) {
            container.launchMissingExtractionBackfill(limit = before.size).join()
        }

        val after = box.all.associateBy { it.id }
        val unfilled = after.values.filter { it.lensReceiptsJson.isNullOrEmpty() || it.lensReceiptsJson == "[]" }
        val zeroAttempts = after.values.filter { it.attemptCount == 0 }

        assertTrue(
            "backfill left ${unfilled.size}/${after.size} rows without lens receipts: ${unfilled.map { it.id }}",
            unfilled.isEmpty(),
        )
        assertTrue(
            "row attempt_count must bump on every terminal extraction — ${zeroAttempts.size}/${after.size}" +
                " rows are still at 0: ${zeroAttempts.map { it.id }}",
            zeroAttempts.isEmpty(),
        )
    }

    private fun mainModelArtifactPresent(): Boolean {
        // Mirror the AppContainer probe without forcing a coroutine: just check that the
        // production model dir holds a non-empty artifact file. False negatives here only
        // cause `assumeTrue` to skip — the production probe still gates real extraction.
        val modelDir = File(app.filesDir, "model")
        if (!modelDir.exists()) return false
        return modelDir.listFiles()?.any { it.isFile && it.length() > 0L } == true
    }

    private companion object {
        // Backfill processes the seeded 35-row corpus serially through the 3-lens worker. GPU
        // budget is ~3-6s/row; 6 min ceiling absorbs slow-path retries without false-positive
        // hangs from interrupting an in-flight extraction.
        private const val BACKFILL_TIMEOUT_MS = 6L * 60 * 1000L
    }
}
