package dev.anchildress1.vestige.ui.history

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.model.ResolvedExtraction
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.storage.closeAfterCleaningThreadResources
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = HistoryTestApplication::class, qualifiers = "w360dp-h800dp")
class HistoryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var tempRoot: File
    private lateinit var dataDir: File
    private lateinit var boxStore: BoxStore
    private lateinit var entryStore: EntryStore
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        tempRoot = newModuleTempRoot("vestige-history-screen-")
        dataDir = newInMemoryObjectBoxDirectory("ob-history-screen-")
        boxStore = openInMemoryBoxStore(dataDir)
        entryStore = EntryStore(boxStore)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        boxStore.closeAfterCleaningThreadResources()
        cleanupObjectBoxTempRoot(tempRoot, dataDir)
    }

    // empty state

    @Test
    fun `HISTORY hero heading is always present`() {
        composeRule.setContent { HistoryScreen(viewModel = newViewModel(), persona = Persona.WITNESS) }
        // The hero heading is the only node carrying the coral-dot "HISTORY." string (the bottom
        // nav's "HISTORY" tab is a separate, period-less label). Always visible, empty + loaded.
        composeRule.onAllNodesWithText("HISTORY.").assertCountEquals(1)
    }

    @Test
    fun `empty state renders locked header copy`() {
        composeRule.setContent { HistoryScreen(viewModel = newViewModel(), persona = Persona.WITNESS) }
        // Shared accentedHeadline uppercases the display form; "YET." is the coral accent token.
        composeRule.onNodeWithText("NOTHING RECORDED YET.").assertIsDisplayed()
    }

    @Test
    fun `empty state renders locked body copy`() {
        composeRule.setContent { HistoryScreen(viewModel = newViewModel(), persona = Persona.WITNESS) }
        composeRule.onNodeWithText("First one takes 30 seconds.").assertIsDisplayed()
    }

    @Test
    fun `empty state is a polite live region with merged copy and no click action`() {
        composeRule.setContent { HistoryScreen(viewModel = newViewModel(), persona = Persona.WITNESS) }
        val band = composeRule.onNodeWithContentDescription("Nothing recorded yet. First one takes 30 seconds.")
        band.assertIsDisplayed()
        band.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        band.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    @Test
    fun `empty state has no history row composables`() {
        composeRule.setContent { HistoryScreen(viewModel = newViewModel(), persona = Persona.WITNESS) }
        composeRule.onAllNodesWithTag("history_row").assertCountEquals(0)
    }

    // filter / search chrome must be absent (Story 4.6: chronological only)

    @Test
    fun `filter affordance does not exist in history screen`() {
        composeRule.setContent { HistoryScreen(viewModel = newViewModel(), persona = Persona.WITNESS) }
        composeRule.onAllNodesWithText("FILTER ▼").assertCountEquals(0)
    }

    // forbidden copy check — nothing from ux-copy.md §"Things to NEVER Write"

    @Test
    fun `forbidden exclamation mark does not appear in empty state`() {
        composeRule.setContent { HistoryScreen(viewModel = newViewModel(), persona = Persona.WITNESS) }
        composeRule.onAllNodesWithText("NOTHING RECORDED YET!").assertCountEquals(0)
    }

    // loaded state

    @Test
    fun `loaded state renders one row per completed entry`() {
        seedCompleted("standup crashed me again", 1_000_000L)
        seedCompleted("woke up fine actually", 2_000_000L)

        composeRule.setContent { HistoryScreen(viewModel = newViewModel(), persona = Persona.WITNESS) }
        composeRule.onAllNodesWithTag("history_row").assertCountEquals(2)
    }

    @Test
    fun `row shows timestamp, snippet and compact duration word-count meta`() {
        // 1_000_000 ms past epoch, UTC → 12:16 AM · JAN 1; seeded duration 0s, 4 words.
        seedCompleted("standup crashed me again", 1_000_000L)

        composeRule.setContent { HistoryScreen(viewModel = newViewModel(), persona = Persona.WITNESS) }
        composeRule.onNodeWithText("12:16 AM").assertIsDisplayed()
        composeRule.onNodeWithText("JAN 1").assertIsDisplayed()
        composeRule.onNodeWithText("standup crashed me again", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("0sec · 4 WORDS").assertIsDisplayed()
    }

    // a11y — tap target ≥ 48 dp

    @Test
    fun `history row tap target is at least 48 dp tall`() {
        seedCompleted("something happened today", 1_000_000L)

        composeRule.setContent { HistoryScreen(viewModel = newViewModel(), persona = Persona.WITNESS) }
        composeRule.onNodeWithTag("history_row").assertHeightIsAtLeast(48.dp)
    }

    // a11y — semantics

    @Test
    fun `history row has click action`() {
        seedCompleted("something happened today", 1_000_000L)

        composeRule.setContent { HistoryScreen(viewModel = newViewModel(), persona = Persona.WITNESS) }
        composeRule.onNodeWithTag("history_row").assertHasClickAction()
    }

    @Test
    fun `history row contentDescription carries timestamp, snippet and meta`() {
        seedCompleted("something happened today", 1_000_000L)

        composeRule.setContent { HistoryScreen(viewModel = newViewModel(), persona = Persona.WITNESS) }
        composeRule.onNodeWithContentDescription(
            "12:16 AM JAN 1 · something happened today · 0sec · 3 words",
        ).assertIsDisplayed()
    }

    // a11y — back navigation is via system BackHandler; no UI back button in this screen

    // shared bottom nav (HISTORY active) replaces the old stat ribbon / density bar

    @Test
    fun `bottom nav reports the selected tab`() {
        var picked: dev.anchildress1.vestige.ui.components.BottomTab? = null
        composeRule.setContent {
            HistoryScreen(
                viewModel = newViewModel(),
                persona = Persona.WITNESS,
                onNavSelect = { picked = it },
            )
        }
        composeRule.onNodeWithText("PATTERNS").performClick()
        assert(picked == dev.anchildress1.vestige.ui.components.BottomTab.PATTERNS)
    }

    private fun newViewModel() = HistoryViewModel(entryStore, zoneId = ZoneOffset.UTC, ioDispatcher = testDispatcher)

    private fun seedCompleted(text: String, timestampEpochMs: Long) {
        val id = entryStore.createPendingEntry(text, Instant.ofEpochMilli(timestampEpochMs))
        entryStore.completeEntry(id, ResolvedExtraction(emptyMap()), templateLabel = null)
    }
}
