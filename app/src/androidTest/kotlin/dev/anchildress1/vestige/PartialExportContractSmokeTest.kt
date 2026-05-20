package dev.anchildress1.vestige

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.vestige.debug.DebugPatternSeeder
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.EntryMarkdownRenderer
import dev.anchildress1.vestige.ui.onboarding.OnboardingPrefs
import dev.anchildress1.vestige.ui.onboarding.OnboardingStep
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * On-device contract checks for the partial-state export — the dump produced before extraction
 * runs. Verifies the on-disk shape that ships from a real device install, not the unit-tested
 * renderer in isolation.
 *
 * Cheap (no model required). Runs against the live `AppContainer` BoxStore via the debug seeder.
 */
@RunWith(AndroidJUnit4::class)
class PartialExportContractSmokeTest {

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        as VestigeApplication
    private val container get() = app.appContainer
    private val prefs get() = OnboardingPrefs.from(app)

    @Before
    fun seedBeforeEach() {
        // Seeder clears all entry/pattern/tag boxes first; idempotent across reruns.
        DebugPatternSeeder.seed(
            filesDir = app.filesDir,
            boxStore = container.boxStore,
            patternStore = container.patternStore,
        )
    }

    @After
    fun resetOnboarding() {
        prefs.reset()
    }

    @Test
    fun seeded_rows_render_tags_as_inline_empty_array() {
        val rows = container.boxStore.boxFor(EntryEntity::class.java).all
        assertTrue("seeder produced no rows — fixture broken", rows.isNotEmpty())

        rows.forEach { entry ->
            val markdown = EntryMarkdownRenderer.render(entry)
            // tags MUST always carry a sequence type. Bare `tags:` parses as null in YAML 1.2.
            if (entry.tags.isEmpty()) {
                assertTrue(
                    "row id=${entry.id} ships bare `tags:` instead of `tags: []`: $markdown",
                    markdown.contains("\ntags: []\n"),
                )
                assertEquals(
                    "row id=${entry.id} ships the malformed bare-key `tags:` form",
                    -1,
                    markdown.indexOf("\ntags:\n"),
                )
            }
        }
    }

    @Test
    fun seeded_rows_carry_documented_seed_contract_status() {
        val rows = container.boxStore.boxFor(EntryEntity::class.java).all
        assertTrue(rows.isNotEmpty())

        // Per AGENTS.md / DebugPatternSeeder contract: seeded rows ship COMPLETED with empty
        // lens_receipts so the History UI can render the demo without paying the on-device
        // extraction cost. EXTRACT=1 backfills via launchMissingExtractionBackfill.
        rows.forEach { entry ->
            assertEquals(
                "row id=${entry.id} extraction_status diverged from seed contract",
                "COMPLETED",
                entry.extractionStatus.name,
            )
            assertTrue(
                "row id=${entry.id} should have empty lens_receipts under seed contract",
                entry.lensReceiptsJson.isNullOrEmpty() || entry.lensReceiptsJson == "[]",
            )
        }
    }

    @Test
    fun export_omits_current_step_when_onboarding_is_complete() {
        // Seeder calls markComplete via DebugSeedReceiver in production, but the unit test path
        // here invokes the seeder directly. Drive the state manually.
        prefs.markComplete()
        assertTrue("markComplete must report success", prefs.isComplete)

        val out = ByteArrayOutputStream()
        VestigeDataExporter(container.boxStore, prefs).writeTo(out)
        val settings = exportSnapshot(out).getJSONObject("settings")

        assertTrue(
            "current_step must be JSON null when onboarding_complete is true — sentinel default " +
                "from OnboardingPrefs is not real resume state",
            settings.isNull("current_step"),
        )
        assertEquals(true, settings.getBoolean("onboarding_complete"))
    }

    @Test
    fun export_keeps_current_step_while_onboarding_is_in_progress() {
        prefs.reset()
        prefs.setCurrentStep(OnboardingStep.Wiring)
        assertFalse(prefs.isComplete)

        val out = ByteArrayOutputStream()
        VestigeDataExporter(container.boxStore, prefs).writeTo(out)
        val settings = exportSnapshot(out).getJSONObject("settings")

        assertEquals("Wiring", settings.getString("current_step"))
        assertEquals(false, settings.getBoolean("onboarding_complete"))
    }

    private fun exportSnapshot(out: ByteArrayOutputStream): JSONObject {
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == VestigeDataExporter.SNAPSHOT_ENTRY) {
                    return JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                }
            }
        }
        error("export archive missing ${VestigeDataExporter.SNAPSHOT_ENTRY}")
    }
}
