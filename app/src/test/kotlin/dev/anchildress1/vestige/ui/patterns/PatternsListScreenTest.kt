package dev.anchildress1.vestige.ui.patterns

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Pos / neg / edge + a11y coverage for the list screen.
 *
 * Err-state handling lives below this layer in the store / repo / viewmodel tests; the screen
 * contract itself receives only stable UI state and action callbacks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = PatternsTestApplication::class)
class PatternsListScreenTest {

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
        tempRoot = newModuleTempRoot("vestige-patterns-list-screen-")
        dataDir = newInMemoryObjectBoxDirectory("ob-list-screen-")
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
    fun `empty state with no entries renders Insufficient data`() {
        composeRule.setContent { PatternsListScreen(viewModel = newViewModel(), onOpenPattern = {}) }
        composeRule.onNodeWithText("Insufficient data.").assertIsDisplayed()
    }

    @Test
    fun `empty state with completed entries but no patterns renders Nothing repeating yet`() {
        seedEntry("entry one", ExtractionStatus.COMPLETED)
        composeRule.setContent { PatternsListScreen(viewModel = newViewModel(), onOpenPattern = {}) }
        composeRule.onNodeWithText("Nothing repeating yet.").assertIsDisplayed()
    }

    @Test
    fun `loaded list surfaces card title observation and source count`() {
        val supporting = listOf(seedEntry("crashed", ExtractionStatus.COMPLETED))
        seedActivePattern(
            patternId = "p-list",
            title = "Tuesday Meetings",
            templateLabel = "Aftermath",
            callout = "Fourth entry mentions Tuesday meetings.",
            supporting = supporting,
        )

        composeRule.setContent { PatternsListScreen(viewModel = newViewModel(), onOpenPattern = {}) }

        composeRule.onNodeWithText("Tuesday Meetings").assertIsDisplayed()
        composeRule.onNodeWithText("Aftermath").assertIsDisplayed()
        composeRule.onNodeWithText("Fourth entry mentions Tuesday meetings.").assertIsDisplayed()
        // Substring match avoids brittleness on the bullet glyph and trailing date format.
        composeRule.onNodeWithText("1 of 1 entries", substring = true).assertIsDisplayed()
    }

    @Test
    fun `section headers render only for sections that have cards`() {
        val supporting = listOf(seedEntry("crashed", ExtractionStatus.COMPLETED))
        seedActivePattern("p-active", "Tuesday Meetings", "Aftermath", "Callout.", supporting)
        seedActivePattern("p-dismissed", "Migration Rewrites", "Decision spiral", "Callout.", supporting)
        patternRepo.dismiss("p-dismissed")

        composeRule.setContent { PatternsListScreen(viewModel = newViewModel(), onOpenPattern = {}) }

        composeRule.onNodeWithText("ACTIVE").assertIsDisplayed()
        composeRule.onNodeWithText("DROPPED").assertIsDisplayed()
        // No snoozed or closed cards in this seed, so those headers must stay hidden.
        composeRule.onAllNodesWithText("SKIPPED · ON HOLD").assertCountEquals(0)
        composeRule.onAllNodesWithText("CLOSED · DONE").assertCountEquals(0)
    }

    @Test
    fun `tapping a card fires onOpenPattern with the right id`() {
        val supporting = listOf(seedEntry("crashed", ExtractionStatus.COMPLETED))
        seedActivePattern("p-tap", "Tuesday Meetings", "Aftermath", "Callout.", supporting)
        var opened: String? = null

        composeRule.setContent {
            PatternsListScreen(viewModel = newViewModel(), onOpenPattern = { opened = it })
        }

        composeRule.onNodeWithText("Tuesday Meetings").performClick()
        assertEquals("p-tap", opened)
    }

    @Test
    fun `loaded card and overflow affordance expose click semantics and labels (a11y)`() {
        val supporting = listOf(seedEntry("crashed", ExtractionStatus.COMPLETED))
        seedActivePattern("p-a11y", "Tuesday Meetings", "Aftermath", "Callout.", supporting)

        composeRule.setContent { PatternsListScreen(viewModel = newViewModel(), onOpenPattern = {}) }

        composeRule.onNodeWithText("Tuesday Meetings").assertHasClickAction()
        composeRule.onNodeWithContentDescription("Pattern actions").assertHasClickAction()
    }

    @Test
    fun `snackbar Undo restores a dismissed pattern back to ACTIVE`() {
        val supporting = listOf(seedEntry("crashed", ExtractionStatus.COMPLETED))
        seedActivePattern("p-undo", "Tuesday Meetings", "Aftermath", "Callout.", supporting)

        composeRule.setContent { PatternsListScreen(viewModel = newViewModel(), onOpenPattern = {}) }

        composeRule.onNodeWithContentDescription("Pattern actions").performClick()
        composeRule.onNodeWithText("Drop").performClick()
        composeRule.waitForIdle()
        // Snackbar surfaces with Undo while the pattern is in DISMISSED state.
        assertEquals(PatternState.DISMISSED, patternStore.findByPatternId("p-undo")?.state)
        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitForIdle()
        assertEquals(PatternState.ACTIVE, patternStore.findByPatternId("p-undo")?.state)
    }

    @Test
    fun `overflow Dismiss action transitions pattern to DISMISSED`() {
        val supporting = listOf(seedEntry("crashed", ExtractionStatus.COMPLETED))
        seedActivePattern("p-dismiss", "Tuesday Meetings", "Aftermath", "Callout.", supporting)

        composeRule.setContent { PatternsListScreen(viewModel = newViewModel(), onOpenPattern = {}) }

        composeRule.onNodeWithContentDescription("Pattern actions").performClick()
        composeRule.onNodeWithText("Drop").performClick()
        composeRule.waitForIdle()

        assertEquals(PatternState.DISMISSED, patternStore.findByPatternId("p-dismiss")?.state)
    }

    @Test
    fun `snoozed cards only expose Restart in overflow menu`() {
        val supporting = listOf(seedEntry("crashed", ExtractionStatus.COMPLETED))
        seedActivePattern("p-snoozed", "Tuesday Meetings", "Aftermath", "Callout.", supporting)
        patternRepo.snooze("p-snoozed")

        composeRule.setContent { PatternsListScreen(viewModel = newViewModel(), onOpenPattern = {}) }

        composeRule.onNodeWithContentDescription("Pattern actions").performClick()
        composeRule.onNodeWithText("Restart").assertIsDisplayed()
        composeRule.onAllNodesWithText("Drop").assertCountEquals(0)
        composeRule.onAllNodesWithText("Skip").assertCountEquals(0)
        composeRule.onAllNodesWithText("Mark resolved").assertCountEquals(0)
    }

    @Test
    fun `dismissed cards expose Restart in overflow menu`() {
        val supporting = listOf(seedEntry("crashed", ExtractionStatus.COMPLETED))
        seedActivePattern("p-dismissed", "Tuesday Meetings", "Aftermath", "Callout.", supporting)
        patternRepo.dismiss("p-dismissed")

        composeRule.setContent { PatternsListScreen(viewModel = newViewModel(), onOpenPattern = {}) }

        composeRule.onNodeWithContentDescription("Pattern actions").performClick()
        composeRule.onNodeWithText("Restart").assertIsDisplayed()
    }

    @Test
    fun `Restart from a dropped card transitions pattern back to ACTIVE`() {
        val supporting = listOf(seedEntry("crashed", ExtractionStatus.COMPLETED))
        seedActivePattern("p-restart-list", "Tuesday Meetings", "Aftermath", "Callout.", supporting)
        patternRepo.dismiss("p-restart-list")

        composeRule.setContent { PatternsListScreen(viewModel = newViewModel(), onOpenPattern = {}) }

        composeRule.onNodeWithContentDescription("Pattern actions").performClick()
        composeRule.onNodeWithText("Restart").performClick()
        composeRule.waitForIdle()

        assertEquals(PatternState.ACTIVE, patternStore.findByPatternId("p-restart-list")?.state)
    }

    private fun newViewModel() = PatternsListViewModel(
        patternStore = patternStore,
        patternRepo = patternRepo,
        entryStore = entryStore,
        ioDispatcher = testDispatcher,
    )

    private fun seedEntry(text: String, status: ExtractionStatus): EntryEntity {
        val entity = EntryEntity(
            entryText = text,
            timestampEpochMs = MAY_12_2026_EPOCH_MS,
            markdownFilename = "entry-${System.nanoTime()}.md",
            extractionStatus = status,
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
        // 2026-05-12T12:00:00Z — keeps the on-card date label deterministic across CI zones.
        const val MAY_12_2026_EPOCH_MS = 1_778_414_400_000L
    }
}
