package dev.anchildress1.vestige.ui.history

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.model.ResolvedExtraction
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.storage.MarkdownEntryStore
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
        entryStore = EntryStore(
            boxStore,
            MarkdownEntryStore(File(tempRoot, "md-${System.nanoTime()}").apply { mkdirs() }),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        boxStore.close()
        cleanupObjectBoxTempRoot(tempRoot, dataDir)
    }

    // empty state

    @Test
    fun `HISTORY hero heading is always present`() {
        composeRule.setContent { HistoryScreen(viewModel = newViewModel(), persona = Persona.WITNESS) }
        // "HISTORY" appears exactly once — the hero display heading (always visible, empty + loaded states)
        composeRule.onAllNodesWithText("HISTORY").assertCountEquals(1)
    }

    @Test
    fun `empty state renders locked header copy`() {
        composeRule.setContent { HistoryScreen(viewModel = newViewModel(), persona = Persona.WITNESS) }
        composeRule.onNodeWithText("No entries yet.").assertIsDisplayed()
    }

    @Test
    fun `empty state renders locked body copy`() {
        composeRule.setContent { HistoryScreen(viewModel = newViewModel(), persona = Persona.WITNESS) }
        composeRule.onNodeWithText("First one takes 30 seconds.").assertIsDisplayed()
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
        composeRule.onAllNodesWithText("No entries yet!").assertCountEquals(0)
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
    fun `row snippet text is visible`() {
        seedCompleted("standup crashed me again", 1_000_000L)

        composeRule.setContent { HistoryScreen(viewModel = newViewModel(), persona = Persona.WITNESS) }
        composeRule.onNodeWithText("standup crashed me again", substring = true).assertIsDisplayed()
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
    fun `history row has non-empty contentDescription`() {
        seedCompleted("something happened today", 1_000_000L)

        composeRule.setContent { HistoryScreen(viewModel = newViewModel(), persona = Persona.WITNESS) }
        composeRule.onNodeWithContentDescription("something happened today", substring = true).assertIsDisplayed()
    }

    // a11y — back navigation is via system BackHandler; no UI back button in this screen

    @Test
    fun `hero eyebrow TAIL ALL TIME is visible`() {
        composeRule.setContent { HistoryScreen(viewModel = newViewModel(), persona = Persona.WITNESS) }
        composeRule.onNodeWithContentDescription("TAIL · ALL TIME", substring = true).assertIsDisplayed()
    }

    // a11y — DensityBar: contentDescription present, not interactive

    @Test
    fun `density bar has contentDescription summarising entry count`() {
        val now = System.currentTimeMillis()
        seedCompleted("entry one", now - 3_600_000L)
        seedCompleted("entry two", now - 7_200_000L)

        composeRule.setContent { HistoryScreen(viewModel = newViewModel(), persona = Persona.WITNESS) }
        composeRule.onNodeWithContentDescription("2 entries in the last 30 days", substring = true).assertIsDisplayed()
    }

    @Test
    fun `density bar has no click action`() {
        val now = System.currentTimeMillis()
        seedCompleted("entry one", now - 3_600_000L)

        composeRule.setContent { HistoryScreen(viewModel = newViewModel(), persona = Persona.WITNESS) }
        composeRule.onNodeWithContentDescription("in the last 30 days", substring = true)
            .assertHasNoClickAction()
    }

    // a11y — StatRibbon: contentDescription present, not interactive

    @Test
    fun `stat ribbon has contentDescription summarising stats`() {
        seedCompleted("entry one", 1_000_000L)
        seedCompleted("entry two", 2_000_000L)

        composeRule.setContent { HistoryScreen(viewModel = newViewModel(), persona = Persona.WITNESS) }
        composeRule.onNodeWithContentDescription("2 entries", substring = true).assertIsDisplayed()
    }

    @Test
    fun `stat ribbon has no click action`() {
        seedCompleted("entry one", 1_000_000L)

        composeRule.setContent { HistoryScreen(viewModel = newViewModel(), persona = Persona.WITNESS) }
        composeRule.onNodeWithContentDescription("1 entry", substring = true).assertHasNoClickAction()
    }

    private fun newViewModel() = HistoryViewModel(entryStore, zoneId = ZoneOffset.UTC, ioDispatcher = testDispatcher)

    private fun seedCompleted(text: String, timestampEpochMs: Long) {
        val id = entryStore.createPendingEntry(text, Instant.ofEpochMilli(timestampEpochMs))
        entryStore.completeEntry(id, ResolvedExtraction(emptyMap()), templateLabel = null)
    }
}
