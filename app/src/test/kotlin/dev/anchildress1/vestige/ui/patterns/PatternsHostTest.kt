package dev.anchildress1.vestige.ui.patterns

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.PatternState
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.storage.MarkdownEntryStore
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.PatternRepo
import dev.anchildress1.vestige.storage.PatternStore
import dev.anchildress1.vestige.testing.cleanupObjectBoxTempRoot
import dev.anchildress1.vestige.testing.newInMemoryObjectBoxDirectory
import dev.anchildress1.vestige.testing.newModuleTempRoot
import dev.anchildress1.vestige.testing.openInMemoryBoxStore
import io.objectbox.BoxStore
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneOffset

/**
 * Exercises the in-process nav graph in PatternsHost. Uses createAndroidComposeRule so the
 * BackHandler wiring (which needs an OnBackPressedDispatcher) has a real Activity host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = PatternsTestApplication::class)
class PatternsHostTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var tempRoot: File
    private lateinit var dataDir: File
    private lateinit var markdownDir: File
    private lateinit var boxStore: BoxStore
    private lateinit var entryStore: EntryStore
    private lateinit var patternStore: PatternStore
    private lateinit var patternRepo: PatternRepo

    @Before
    fun setUp() {
        tempRoot = newModuleTempRoot("vestige-patterns-host-")
        dataDir = newInMemoryObjectBoxDirectory("ob-host-")
        markdownDir = File(tempRoot, "md-${System.nanoTime()}").apply { mkdirs() }
        boxStore = openInMemoryBoxStore(dataDir)
        entryStore = EntryStore(
            boxStore,
            MarkdownEntryStore(markdownDir),
        )
        patternStore = PatternStore(boxStore)
        patternRepo = PatternRepo(patternStore)
    }

    @After
    fun tearDown() {
        boxStore.close()
        cleanupObjectBoxTempRoot(tempRoot, dataDir)
    }

    @Test
    fun `renders list by default and routes through detail then entry detail`() {
        val supporting = listOf(seedEntry("crashed after standup"))
        seedActivePattern("p-host", "Tuesday Meetings", "Aftermath", "Callout.", supporting)

        composeRule.activity.setContent {
            PatternsHost(
                patternStore = patternStore,
                patternRepo = patternRepo,
                entryStore = entryStore,
                persona = Persona.WITNESS,
                zoneId = ZoneOffset.UTC,
            )
        }

        // List visible.
        composeRule.onNodeWithText("Tuesday Meetings").assertIsDisplayed()

        // List → detail.
        composeRule.onNodeWithText("Tuesday Meetings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Callout.").assertIsDisplayed()

        // Detail → entry detail (real EntryDetailScreen; bottom bar is the stable navigation landmark).
        composeRule.onNodeWithText("crashed after standup").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("● NEW ENTRY").assertIsDisplayed()
    }

    @Test
    fun `onExit fires when back is invoked from the list level`() {
        var exited = false
        composeRule.activity.setContent {
            PatternsHost(
                patternStore = patternStore,
                patternRepo = patternRepo,
                entryStore = entryStore,
                persona = Persona.WITNESS,
                zoneId = ZoneOffset.UTC,
                onExit = { exited = true },
            )
        }

        composeRule.activity.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        assertTrue("onExit should fire when back is pressed at list level", exited)
    }

    private fun seedEntry(text: String): EntryEntity {
        val entity = EntryEntity(
            entryText = text,
            timestampEpochMs = MAY_12_2026_EPOCH_MS,
            markdownFilename = "entry-${System.nanoTime()}.md",
            extractionStatus = ExtractionStatus.COMPLETED,
        )
        boxStore.boxFor(EntryEntity::class.java).put(entity)
        return entity
    }

    private fun seedActivePattern(
        patternId: String,
        title: String,
        templateLabel: String,
        callout: String,
        supporting: List<EntryEntity>,
    ) {
        val entity = PatternEntity(
            patternId = patternId,
            kind = PatternKind.TEMPLATE_RECURRENCE,
            signatureJson = "{}",
            title = title,
            templateLabel = templateLabel,
            firstSeenTimestamp = MAY_12_2026_EPOCH_MS,
            lastSeenTimestamp = MAY_12_2026_EPOCH_MS,
            state = PatternState.ACTIVE,
            stateChangedTimestamp = MAY_12_2026_EPOCH_MS,
            latestCalloutText = callout,
        )
        boxStore.boxFor(PatternEntity::class.java).put(entity)
        val saved = patternStore.findByPatternId(patternId)
            ?: error("pattern not persisted: $patternId")
        saved.supportingEntries.addAll(supporting)
        boxStore.boxFor(PatternEntity::class.java).put(saved)
    }

    private companion object {
        const val MAY_12_2026_EPOCH_MS = 1_778_414_400_000L
    }
}
