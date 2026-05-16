package dev.anchildress1.vestige.ui.patterns

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
    fun `Day-1 empty state renders the eyebrow header and body text`() {
        composeRule.setContent { PatternsListScreen(viewModel = newViewModel(), onOpenPattern = {}) }
        composeRule.onNodeWithText("VESTIGES · 0 ENTRIES · 30 DAYS").assertIsDisplayed()
        composeRule.onNodeWithText("Nothing to read yet.").assertIsDisplayed()
        composeRule.onNodeWithText("Patterns surface after 10 entries. Keep recording.").assertIsDisplayed()
    }

    @Test
    fun `Day-1 eyebrow substitutes the live entry count below the detection threshold`() {
        // Nine COMPLETED entries — one below PATTERN_SURFACE_MIN_ENTRIES, so still NO_ENTRIES.
        // Proves the %1$d substitution in patterns_empty_day1_eyebrow, not just count==0.
        repeat(9) { seedEntry("entry $it", ExtractionStatus.COMPLETED) }
        composeRule.setContent { PatternsListScreen(viewModel = newViewModel(), onOpenPattern = {}) }
        composeRule.onNodeWithText("VESTIGES · 9 ENTRIES · 30 DAYS").assertIsDisplayed()
    }

    @Test
    fun `Day-1 empty state is an announced status band with no click action (a11y)`() {
        composeRule.setContent { PatternsListScreen(viewModel = newViewModel(), onOpenPattern = {}) }
        // AGENTS.md band rule: merged contentDescription + polite liveRegion present, NO click.
        val mergedDescription =
            "VESTIGES · 0 ENTRIES · 30 DAYS Nothing to read yet. " +
                "Patterns surface after 10 entries. Keep recording."
        val band = composeRule.onNodeWithContentDescription(mergedDescription)
        band.assertIsDisplayed()
        band.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        band.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    @Test
    fun `NO_PATTERNS empty state renders header and body but no eyebrow`() {
        repeat(10) { seedEntry("entry $it", ExtractionStatus.COMPLETED) }
        composeRule.setContent { PatternsListScreen(viewModel = newViewModel(), onOpenPattern = {}) }
        composeRule.onNodeWithText("No repeating pattern detected.").assertIsDisplayed()
        composeRule.onNodeWithText("The model looked. Nothing came back twice.").assertIsDisplayed()
        composeRule.onAllNodesWithText("VESTIGES", substring = true).assertCountEquals(0)
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
        seedActivePattern("p-dropped", "Migration Rewrites", "Decision spiral", "Callout.", supporting)
        patternRepo.drop("p-dropped")

        composeRule.setContent { PatternsListScreen(viewModel = newViewModel(), onOpenPattern = {}) }

        composeRule.onNodeWithText("ACTIVE").assertIsDisplayed()
        composeRule.onNodeWithText("DROPPED").assertIsDisplayed()
        // No skipped or closed cards in this seed, so those headers must stay hidden.
        composeRule.onAllNodesWithText("SKIPPED · ON HOLD").assertCountEquals(0)
        composeRule.onAllNodesWithText("CLOSED · DONE").assertCountEquals(0)
    }

    @Test
    fun `skipped and dropped sections both render their headers and CLOSED is omitted`() {
        // Two non-active sections + their cards fit the default Robolectric viewport (the same
        // shape as the proven ACTIVE+DROPPED case). Fixed ACTIVE→SKIPPED→CLOSED→DROPPED render
        // order is exhaustively asserted at the PatternSection / ViewModel tier — this guards
        // the screen-level section grouping + the CLOSED-omission gate.
        val supporting = listOf(seedEntry("crashed", ExtractionStatus.COMPLETED))
        seedActivePattern("p-skp", "Skipped One", "Aftermath", "Callout.", supporting)
        seedActivePattern("p-drp", "Dropped One", "Aftermath", "Callout.", supporting)
        patternRepo.skip("p-skp")
        patternRepo.drop("p-drp")

        composeRule.setContent { PatternsListScreen(viewModel = newViewModel(), onOpenPattern = {}) }

        composeRule.onNodeWithText("SKIPPED · ON HOLD").assertIsDisplayed()
        composeRule.onNodeWithText("Skipped One").assertIsDisplayed()
        composeRule.onNodeWithText("DROPPED").assertIsDisplayed()
        composeRule.onNodeWithText("Dropped One").assertIsDisplayed()
        // Empty sections render no header — no ACTIVE pattern, no CLOSED pattern.
        composeRule.onAllNodesWithText("ACTIVE").assertCountEquals(0)
        composeRule.onAllNodesWithText("CLOSED · DONE").assertCountEquals(0)
    }

    @Test
    fun `dropped section card renders and is not the active lime-rule card`() {
        val supporting = listOf(seedEntry("crashed", ExtractionStatus.COMPLETED))
        seedActivePattern("p-dropped-card", "Dropped Card", "Aftermath", "Callout.", supporting)
        patternRepo.drop("p-dropped-card")

        composeRule.setContent { PatternsListScreen(viewModel = newViewModel(), onOpenPattern = {}) }

        // Alpha de-prioritizes the card but it stays present + tappable; it's under DROPPED.
        composeRule.onNodeWithText("Dropped Card").assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithText("DROPPED").assertIsDisplayed()
        composeRule.onAllNodesWithText("ACTIVE").assertCountEquals(0)
    }

    @Test
    fun `skipped card surfaces the Back wake-up date line`() {
        val supporting = listOf(seedEntry("crashed", ExtractionStatus.COMPLETED))
        seedActivePattern("p-skip-back", "Skipped Card", "Aftermath", "Callout.", supporting)
        patternRepo.skip("p-skip-back")

        composeRule.setContent { PatternsListScreen(viewModel = newViewModel(), onOpenPattern = {}) }

        // skip() default = 7 days from now; assert the "Back <date>." line is present.
        composeRule.onNodeWithText("Back ", substring = true).assertIsDisplayed()
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
    fun `snackbar Undo restores a dropped pattern back to ACTIVE`() {
        val supporting = listOf(seedEntry("crashed", ExtractionStatus.COMPLETED))
        seedActivePattern("p-undo", "Tuesday Meetings", "Aftermath", "Callout.", supporting)

        composeRule.setContent { PatternsListScreen(viewModel = newViewModel(), onOpenPattern = {}) }

        composeRule.onNodeWithContentDescription("Pattern actions").performClick()
        composeRule.onNodeWithText("Drop").performClick()
        composeRule.waitForIdle()
        // Snackbar surfaces with Undo while the pattern is in DROPPED state.
        assertEquals(PatternState.DROPPED, patternStore.findByPatternId("p-undo")?.state)
        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitForIdle()
        assertEquals(PatternState.ACTIVE, patternStore.findByPatternId("p-undo")?.state)
    }

    @Test
    fun `overflow Drop action transitions pattern to DROPPED`() {
        val supporting = listOf(seedEntry("crashed", ExtractionStatus.COMPLETED))
        seedActivePattern("p-drop", "Tuesday Meetings", "Aftermath", "Callout.", supporting)

        composeRule.setContent { PatternsListScreen(viewModel = newViewModel(), onOpenPattern = {}) }

        composeRule.onNodeWithContentDescription("Pattern actions").performClick()
        composeRule.onNodeWithText("Drop").performClick()
        composeRule.waitForIdle()

        assertEquals(PatternState.DROPPED, patternStore.findByPatternId("p-drop")?.state)
    }

    @Test
    fun `active overflow exposes Drop and Skip and no retired action labels`() {
        val supporting = listOf(seedEntry("crashed", ExtractionStatus.COMPLETED))
        seedActivePattern("p-active-overflow", "Tuesday Meetings", "Aftermath", "Callout.", supporting)

        composeRule.setContent { PatternsListScreen(viewModel = newViewModel(), onOpenPattern = {}) }

        composeRule.onNodeWithContentDescription("Pattern actions").performClick()
        composeRule.onNodeWithText("Drop").assertIsDisplayed()
        composeRule.onNodeWithText("Skip").assertIsDisplayed()
        composeRule.onAllNodesWithText("Restart").assertCountEquals(0)
        composeRule.onAllNodesWithText("Done").assertCountEquals(0)
        composeRule.onAllNodesWithText("Mark resolved").assertCountEquals(0)
        composeRule.onAllNodesWithText("Snooze").assertCountEquals(0)
        composeRule.onAllNodesWithText("Dismiss").assertCountEquals(0)
    }

    @Test
    fun `skipped cards only expose Restart in overflow menu`() {
        val supporting = listOf(seedEntry("crashed", ExtractionStatus.COMPLETED))
        seedActivePattern("p-skipped", "Tuesday Meetings", "Aftermath", "Callout.", supporting)
        patternRepo.skip("p-skipped")

        composeRule.setContent { PatternsListScreen(viewModel = newViewModel(), onOpenPattern = {}) }

        composeRule.onNodeWithContentDescription("Pattern actions").performClick()
        composeRule.onNodeWithText("Restart").assertIsDisplayed()
        composeRule.onAllNodesWithText("Drop").assertCountEquals(0)
        composeRule.onAllNodesWithText("Skip").assertCountEquals(0)
        composeRule.onAllNodesWithText("Mark resolved").assertCountEquals(0)
    }

    @Test
    fun `dropped cards expose Restart in overflow menu`() {
        val supporting = listOf(seedEntry("crashed", ExtractionStatus.COMPLETED))
        seedActivePattern("p-dropped", "Tuesday Meetings", "Aftermath", "Callout.", supporting)
        patternRepo.drop("p-dropped")

        composeRule.setContent { PatternsListScreen(viewModel = newViewModel(), onOpenPattern = {}) }

        composeRule.onNodeWithContentDescription("Pattern actions").performClick()
        composeRule.onNodeWithText("Restart").assertIsDisplayed()
    }

    @Test
    fun `Restart from a dropped card transitions pattern back to ACTIVE`() {
        val supporting = listOf(seedEntry("crashed", ExtractionStatus.COMPLETED))
        seedActivePattern("p-restart-list", "Tuesday Meetings", "Aftermath", "Callout.", supporting)
        patternRepo.drop("p-restart-list")

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
