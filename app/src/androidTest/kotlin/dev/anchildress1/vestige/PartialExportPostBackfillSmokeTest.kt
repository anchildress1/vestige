package dev.anchildress1.vestige

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.storage.CalloutCooldownEntity
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.EntryMarkdownRenderer
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.TagEntity
import dev.anchildress1.vestige.ui.onboarding.OnboardingPrefs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.ZipInputStream

/**
 * End-to-end on-device smoke: real LLM generates real extraction output for a known corpus,
 * then the export archive is verified against every data-contract fix this branch landed.
 *
 * Each row in the test corpus is a transcript lifted from the existing
 * `DemoExamplesSmokeTest` cases, so we already know what the model converges on. The smoke
 * runs the full path the dump originated from:
 *   seed (COMPLETED + empty receipts) → launchMissingExtractionBackfill (real engine)
 *     → VestigeDataExporter.writeTo → unzip → assert.
 *
 * Asserts (against real model output, not seed text):
 *   - Fix #1: empty-tag rows ship `tags: []` inline; populated rows ship the multi-line block.
 *   - Fix #3: `entries[].attempt_count` is > 0 on every successfully-backfilled row.
 *   - Fix #5: `settings.current_step` is JSON null because onboarding is complete.
 *   - Seed-contract honesty: post-backfill, status is COMPLETED and receipts are non-empty.
 *   - Anticipated-content: at least one of the documented expected-tag variants for each row
 *     lands in the resolved tags (loose match — same normalization DemoExamples uses).
 *
 * Requires `modelPath` instrumentation arg, otherwise self-skips via `assumeTrue`.
 *
 * Run:
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -PmodelPath=/data/local/tmp/gemma-4-E4B-it.litertlm \
 *     -PinferenceBackend=gpu \
 *     -Pandroid.testInstrumentationRunnerArguments.class=\
 *       dev.anchildress1.vestige.PartialExportPostBackfillSmokeTest
 */
@RunWith(AndroidJUnit4::class)
class PartialExportPostBackfillSmokeTest {

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        as VestigeApplication
    private val container get() = app.appContainer
    private val prefs get() = OnboardingPrefs.from(app)

    @Before
    fun seedKnownCorpus() {
        // Wipe everything the exporter walks. We don't call DebugPatternSeeder.seed() here —
        // its 35-row corpus would push backfill past the 3-minute ceiling. Replicate only the
        // documented seed contract (COMPLETED + empty receipts) for three known transcripts.
        container.boxStore.runInTx {
            container.boxStore.boxFor(EntryEntity::class.java).removeAll()
            container.boxStore.boxFor(PatternEntity::class.java).removeAll()
            container.boxStore.boxFor(TagEntity::class.java).removeAll()
            container.boxStore.boxFor(CalloutCooldownEntity::class.java).removeAll()

            val box = container.boxStore.boxFor(EntryEntity::class.java)
            CORPUS.forEach { sample ->
                box.put(
                    EntryEntity(
                        markdownFilename = "smoke-${sample.id}.md",
                        entryText = sample.entryText,
                        timestampEpochMs = Instant.parse(sample.timestampIso).toEpochMilli(),
                        durationMs = 18_000L,
                        extractionStatus = ExtractionStatus.COMPLETED,
                    ),
                )
            }
        }
    }

    @After
    fun resetState() {
        prefs.reset()
        // Leave the box wiped so an interactive `make reinstall` after this run starts clean.
        container.boxStore.runInTx {
            container.boxStore.boxFor(EntryEntity::class.java).removeAll()
            container.boxStore.boxFor(PatternEntity::class.java).removeAll()
            container.boxStore.boxFor(TagEntity::class.java).removeAll()
            container.boxStore.boxFor(CalloutCooldownEntity::class.java).removeAll()
        }
    }

    @Test
    fun backfill_produces_export_that_honors_every_fix() = runBlocking {
        assumeTrue(
            "main model artifact missing — run `make push-model` before this smoke",
            mainModelArtifactPresent(),
        )
        prefs.markComplete()

        // 1. Pre-backfill snapshot to anchor the seed contract.
        val entryBox = container.boxStore.boxFor(EntryEntity::class.java)
        val before = entryBox.all
        assertEquals("seed corpus size mismatch", CORPUS.size, before.size)
        before.forEach { row ->
            assertTrue(
                "seed row id=${row.id} should start with empty lens_receipts",
                row.lensReceiptsJson.isNullOrEmpty() || row.lensReceiptsJson == "[]",
            )
            assertEquals("seed row id=${row.id} should start at attempt_count=0", 0, row.attemptCount)
        }

        // 2. Real engine fires three lenses per row.
        withTimeout(BACKFILL_TIMEOUT_MS) {
            container.launchMissingExtractionBackfill(limit = before.size).join()
        }

        // 3. Export the exact archive a user would receive from Settings → Export.
        val out = ByteArrayOutputStream()
        VestigeDataExporter(container.boxStore, prefs).writeTo(out)
        val archive = unzip(out)
        val snapshot = JSONObject(
            archive[VestigeDataExporter.SNAPSHOT_ENTRY]
                ?: error("export archive missing ${VestigeDataExporter.SNAPSHOT_ENTRY}"),
        )

        // 4. Fix #5 — sentinel suppression in real export.
        val settings = snapshot.getJSONObject("settings")
        assertEquals(true, settings.getBoolean("onboarding_complete"))
        assertTrue(
            "current_step must be JSON null on a completed-onboarding export; got $settings",
            settings.isNull("current_step"),
        )

        // 5. Per-entry post-backfill asserts: fixes #3, #1, and anticipated-content correctness.
        val entries = snapshot.getJSONArray("entries")
        assertEquals(CORPUS.size, entries.length())
        val rowsById = entryBox.all.associateBy { it.markdownFilename }
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val filename = entry.getString("markdown_filename")
            val sample = CORPUS.firstOrNull { filename.contains(it.id) }
                ?: error("export carried unknown markdown_filename $filename")
            val tags = (0 until entry.getJSONArray("tags").length())
                .map { entry.getJSONArray("tags").getString(it) }

            // Fix #3 (end-to-end): attempt_count bumped through real EntryStore.completeEntry.
            val attemptCount = entry.getInt("attempt_count")
            assertNotEquals(
                "row ${sample.id}: attempt_count must bump on every terminal completion",
                0,
                attemptCount,
            )

            // Seed-contract honesty post-backfill: status is COMPLETED *and* receipts are real.
            assertEquals("COMPLETED", entry.getString("extraction_status"))
            val receipts = entry.getString("lens_receipts_json")
            assertTrue(
                "row ${sample.id}: post-backfill lens_receipts must be non-empty; was $receipts",
                receipts.isNotBlank() && receipts != "[]",
            )

            // Anticipated content: at least one documented tag variant lands in real output.
            // Loose match: normalize to [a-z0-9] so kebab/space/dash variants collide with intent.
            val anticipated = sample.expectedTags.map(::normalize)
            val produced = tags.map(::normalize)
            assertTrue(
                "row ${sample.id}: no anticipated tag landed.\n" +
                    "  anticipated: ${sample.expectedTags}\n" +
                    "  produced:    $tags",
                anticipated.any { expected -> produced.any { it == expected || it.contains(expected) } },
            )

            // Fix #1: markdown shape matches tag cardinality.
            val markdown = archive["${VestigeDataExporter.MARKDOWN_EXPORT_DIR}/$filename"]
                ?: error("export archive missing markdown body for $filename")
            // EntryMarkdownRenderer is the authority; re-derive against the row we exported.
            val row = rowsById[filename] ?: error("BoxStore lost row for $filename")
            val expectedTagBlock = if (row.tags.isEmpty()) "\ntags: []\n" else "\ntags:\n"
            assertTrue(
                "row ${sample.id}: markdown tags block must match cardinality (${row.tags.count()} tags) " +
                    "— expected `$expectedTagBlock`",
                markdown.contains(expectedTagBlock),
            )
            // Sanity: bare `tags:` form must NEVER reach the export when tags are empty.
            if (row.tags.isEmpty()) {
                assertEquals(
                    "row ${sample.id}: empty-tag row leaked bare `tags:` form into markdown",
                    -1,
                    markdown.indexOf("\ntags:\n"),
                )
            }
            // Sanity: rendered markdown is exactly what the live renderer would emit for the row.
            assertEquals(
                "row ${sample.id}: archived markdown diverged from EntryMarkdownRenderer output",
                EntryMarkdownRenderer.render(row),
                markdown,
            )
        }
    }

    private fun unzip(out: ByteArrayOutputStream): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return entries
    }

    private fun mainModelArtifactPresent(): Boolean {
        val modelDir = File(app.filesDir, "model")
        if (!modelDir.exists()) return false
        return modelDir.listFiles()?.any { it.isFile && it.length() > 0L } == true
    }

    private fun normalize(token: String): String = token.lowercase().replace(Regex("[^a-z0-9]+"), "")

    private data class CorpusSample(
        val id: String,
        val timestampIso: String,
        val entryText: String,
        val expectedTags: Set<String>,
    )

    private companion object {
        // Budget: 3 rows × 3 lenses × ~10s GPU ≈ 90s; 3-min ceiling absorbs cold-warmup slop and
        // a single slow lens without false-positive hangs on the first row's engine warmup.
        private const val BACKFILL_TIMEOUT_MS = 3L * 60 * 1000L

        // Inputs + anticipated tags mirror DemoExamplesSmokeTest.CASES — the same corpus that
        // already documents what the model converges on. We assert against the resolved-tag
        // surface, which is the field the partial dump complained about.
        private val CORPUS = listOf(
            CorpusSample(
                id = "hollow-thing",
                timestampIso = "2026-05-19T20:19:09Z",
                entryText =
                "after the all hands i did the hollow thing again my coffee went cold on " +
                    "the desk and the thing i was going to do right after that kind of " +
                    "vaped while reading i had three tabs open i knew with the three tabs " +
                    "are four and they're still sitting there open",
                expectedTags = setOf("hollow", "tabs", "meeting", "all-hands"),
            ),
            CorpusSample(
                id = "package-loop",
                timestampIso = "2026-05-19T20:20:09Z",
                entryText =
                "said i would drop the package off today. drive past ups on my route. " +
                    "spent twenty minutes googling whether the thing is even worth " +
                    "returning. it is. label is still on the counter.",
                expectedTags = setOf("package", "ups", "label", "counter"),
            ),
            CorpusSample(
                id = "couch-loop",
                timestampIso = "2026-05-19T20:21:25Z",
                entryText =
                "spent an hour and a half comparing couches. dimensions, reviews, lead " +
                    "time, return policy. made a spreadsheet. did not buy a couch. " +
                    "twelve rows.",
                expectedTags = setOf("couch", "spreadsheet", "comparing"),
            ),
        )
    }
}
