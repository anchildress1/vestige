package dev.anchildress1.vestige.ui.patterns

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.PatternState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Pos / neg / edge + a11y coverage for the detail screen.
 *
 * Err-state handling stays in repo / viewmodel tests; this composable only renders resolved UI
 * state plus callback wiring.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = PatternsTestApplication::class)
class PatternDetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var tempRoot: File
    private lateinit var dataDir: File
    private lateinit var markdownDir: File
    private lateinit var boxStore: BoxStore
    private lateinit var entryStore: EntryStore
    private lateinit var patternStore: PatternStore
    private lateinit var patternRepo: PatternRepo
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        tempRoot = newModuleTempRoot("vestige-pattern-detail-screen-")
        dataDir = newInMemoryObjectBoxDirectory("ob-detail-screen-")
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
        Dispatchers.resetMain()
        boxStore.close()
        cleanupObjectBoxTempRoot(tempRoot, dataDir)
    }

    @Test
    fun `NotFound state renders fallback copy`() {
        composeRule.setContent {
            PatternDetailScreen(viewModel = newViewModel("missing"), onBack = {})
        }
        composeRule.onNodeWithText("Pattern not found.").assertIsDisplayed()
    }

    @Test
    fun `Loaded state renders title observation sources and action row`() {
        val supporting = listOf(seedEntry("crashed after standup"))
        seedActivePattern(
            patternId = "p-detail-render",
            title = "Tuesday Meetings",
            templateLabel = "Aftermath",
            callout = "Fourth entry mentions Tuesday meetings.",
            supporting = supporting,
        )

        composeRule.setContent {
            PatternDetailScreen(viewModel = newViewModel("p-detail-render"), onBack = {})
        }

        composeRule.onNodeWithText("Tuesday Meetings").assertIsDisplayed()
        composeRule.onNodeWithText("Aftermath").assertIsDisplayed()
        composeRule.onNodeWithText("Fourth entry mentions Tuesday meetings.").assertIsDisplayed()
        // Action row + sources live below the new card stack; scrolling brings them into view.
        composeRule.onNodeWithText("crashed after standup").performScrollTo().assertIsDisplayed()
        // Active patterns expose user actions Drop + Skip only; Restart belongs to terminal patterns.
        composeRule.onNodeWithText("Drop").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Skip").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Mark resolved").assertCountEquals(0)
        composeRule.onAllNodesWithText("Done").assertCountEquals(0)
        composeRule.onAllNodesWithText("Restart").assertCountEquals(0)
    }

    @Test
    fun `terminal dismissed pattern surfaces the terminal label and Restart action`() {
        val supporting = listOf(seedEntry("crashed"))
        seedActivePattern("p-terminal", "Tuesday Meetings", "Aftermath", "Callout.", supporting)
        patternRepo.dismiss("p-terminal")

        composeRule.setContent {
            PatternDetailScreen(viewModel = newViewModel("p-terminal"), onBack = {})
        }

        composeRule.onNodeWithText("Dropped", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Drop").assertCountEquals(0)
        composeRule.onAllNodesWithText("Skip").assertCountEquals(0)
        composeRule.onAllNodesWithText("Mark resolved").assertCountEquals(0)
        composeRule.onNodeWithText("Restart").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `snoozed detail only exposes Restart`() {
        val supporting = listOf(seedEntry("crashed"))
        seedActivePattern("p-snoozed-detail", "Tuesday Meetings", "Aftermath", "Callout.", supporting)
        patternRepo.snooze("p-snoozed-detail")

        composeRule.setContent {
            PatternDetailScreen(viewModel = newViewModel("p-snoozed-detail"), onBack = {})
        }

        composeRule.onNodeWithText("Restart").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Drop").assertCountEquals(0)
        composeRule.onAllNodesWithText("Skip").assertCountEquals(0)
        composeRule.onAllNodesWithText("Mark resolved").assertCountEquals(0)
    }

    @Test
    fun `Restart button on a dismissed pattern transitions it back to ACTIVE`() {
        val supporting = listOf(seedEntry("crashed"))
        seedActivePattern("p-restart-click", "Tuesday Meetings", "Aftermath", "Callout.", supporting)
        patternRepo.dismiss("p-restart-click")

        composeRule.setContent {
            PatternDetailScreen(viewModel = newViewModel("p-restart-click"), onBack = {})
        }

        composeRule.onNodeWithText("Restart").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(PatternState.ACTIVE, patternStore.findByPatternId("p-restart-click")?.state)
    }

    @Test
    fun `Back navigation icon fires onBack`() {
        var backFired = false
        composeRule.setContent {
            PatternDetailScreen(
                viewModel = newViewModel("missing"),
                onBack = { backFired = true },
            )
        }
        composeRule.onNodeWithContentDescription("Back").performClick()
        assertTrue("back callback fired", backFired)
    }

    @Test
    fun `Back navigation icon exposes an accessible click target (a11y)`() {
        composeRule.setContent {
            PatternDetailScreen(
                viewModel = newViewModel("missing"),
                onBack = {},
            )
        }
        composeRule.onNodeWithContentDescription("Back").assertHasClickAction()
    }

    @Test
    fun `tapping a source row fires onOpenEntry with that entry id`() {
        val supporting = listOf(seedEntry("crashed after standup"))
        seedActivePattern("p-source-tap", "Tuesday Meetings", "Aftermath", "Callout.", supporting)
        var openedEntryId: Long? = null

        composeRule.setContent {
            PatternDetailScreen(
                viewModel = newViewModel("p-source-tap"),
                onBack = {},
                onOpenEntry = { openedEntryId = it },
            )
        }

        composeRule.onNodeWithText("crashed after standup").performScrollTo().performClick()
        assertEquals(supporting.single().id, openedEntryId)
    }

    private fun newViewModel(patternId: String) = PatternDetailViewModel(
        patternId = patternId,
        patternStore = patternStore,
        patternRepo = patternRepo,
        entryStore = entryStore,
        ioDispatcher = testDispatcher,
    )

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
