package dev.anchildress1.vestige.ui.history

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.anchildress1.vestige.model.ConfidenceVerdict
import dev.anchildress1.vestige.model.EntryLensReceipt
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.Lens
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.model.ResolvedExtraction
import dev.anchildress1.vestige.model.ResolvedField
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.storage.MarkdownEntryStore
import dev.anchildress1.vestige.testing.cleanupObjectBoxTempRoot
import dev.anchildress1.vestige.testing.newInMemoryObjectBoxDirectory
import dev.anchildress1.vestige.testing.newModuleTempRoot
import dev.anchildress1.vestige.testing.openInMemoryBoxStore
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import io.objectbox.BoxStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
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
class EntryDetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var tempRoot: File
    private lateinit var dataDir: File
    private lateinit var boxStore: BoxStore
    private lateinit var entryStore: EntryStore
    private val dispatcher = UnconfinedTestDispatcher()
    private val zone = ZoneOffset.UTC

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        tempRoot = newModuleTempRoot("vestige-entry-detail-screen-")
        dataDir = newInMemoryObjectBoxDirectory("ob-entry-detail-screen-")
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

    @Test
    fun `time hero and transcription are displayed`() {
        val id = createCompleted("standup was brutal today")
        setDetail(id)
        composeRule.onNodeWithTag("entry_time").assertIsDisplayed()
        // Transcript is the very-bottom block now — it exists in the scroll, below the fold.
        composeRule.onNodeWithTag("entry_transcription").assertExists()
        composeRule.onNodeWithText("standup was brutal today").assertExists()
    }

    @Test
    fun `follow-up card is shown with persona eyebrow and a11y`() {
        val id = createCompleted(
            text = "standup was brutal today",
            followUpText = "What did you do right after it ended?",
            persona = Persona.HARDASS,
        )
        setDetail(id)
        composeRule.onNodeWithTag("entry_follow_up").assertIsDisplayed()
        composeRule.onNodeWithText("What did you do right after it ended?").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "HARDASS · FOLLOW-UP: What did you do right after it ended?",
        ).assertIsDisplayed()
    }

    @Test
    fun `follow-up card absent when no follow-up saved`() {
        val id = createCompleted("no follow up here")
        setDetail(id)
        composeRule.onAllNodesWithTag("entry_follow_up").assertCountEquals(0)
    }

    @Test
    fun `resolved view shows persisted three-lens receipts and field grid`() {
        val id = entryStore.createPendingEntry("battery got yanked", FIXTURE_INSTANT)
        entryStore.completeEntry(
            id,
            ResolvedExtraction(
                mapOf(
                    "tags" to ResolvedField(listOf("meeting", "battery-yanked"), ConfidenceVerdict.CANONICAL),
                    "energy_descriptor" to ResolvedField(
                        "crashed",
                        ConfidenceVerdict.CANONICAL_WITH_CONFLICT,
                    ),
                ),
            ),
            null,
            lensReceipts = listOf(
                EntryLensReceipt(
                    lens = Lens.LITERAL,
                    extracted = true,
                    fields = mapOf("energy_descriptor" to "battery yanked"),
                ),
                EntryLensReceipt(
                    lens = Lens.INFERENTIAL,
                    extracted = true,
                    fields = mapOf("energy_descriptor" to "post-meeting energy crash"),
                ),
                EntryLensReceipt(
                    lens = Lens.SKEPTICAL,
                    extracted = true,
                    fields = mapOf("energy_descriptor" to "not tired vs yanked"),
                    flags = listOf("vocabulary-contradiction:not tired:battery yanked"),
                ),
            ),
        )
        setDetail(id)
        composeRule.onNodeWithTag("entry_three_lens").assertIsDisplayed()
        composeRule.onNodeWithTag("entry_field_grid").assertIsDisplayed()
        composeRule.onNodeWithText(EntryDetailCopy.THREE_LENS_EYEBROW).assertIsDisplayed()
        composeRule.onNodeWithText("battery yanked").assertIsDisplayed()
        composeRule.onNodeWithText("crashed").assertIsDisplayed()
        composeRule.onNodeWithText(EntryDetailCopy.THREE_LENS_STATUS_CONFLICT).assertIsDisplayed()
        // The extracting/skeleton branch is not the resolved view.
        composeRule.onAllNodesWithTag("entry_extracting").assertCountEquals(0)
    }

    @Test
    fun `pending entry shows the extracting branch, not the resolved read`() {
        val id = entryStore.createPendingEntry("still extracting", FIXTURE_INSTANT)
        setDetail(id)
        composeRule.onNodeWithTag("entry_extracting").assertExists()
        composeRule.onAllNodesWithTag("entry_three_lens").assertCountEquals(0)
        composeRule.onAllNodesWithTag("entry_field_grid").assertCountEquals(0)
    }

    @Test
    fun `failed entry shows failure band instead of endless extracting`() {
        val id = entryStore.createPendingEntry("failed extraction", FIXTURE_INSTANT)
        entryStore.failEntry(id, ExtractionStatus.FAILED, "lens-failed")

        setDetail(id)

        composeRule.onNodeWithTag("entry_extracting_failed").assertExists()
        composeRule.onAllNodesWithTag("entry_extracting").assertCountEquals(0)
        composeRule.onAllNodesWithTag("entry_lens_skeleton").assertCountEquals(0)
    }

    @Test
    fun `blank transcription shows dash placeholder`() {
        val id = createCompleted("initial text")
        val box = boxStore.boxFor(EntryEntity::class.java)
        val entity = box.get(id)
        entity.entryText = ""
        box.put(entity)
        setDetail(id)
        composeRule.onNodeWithTag("entry_transcription").assertExists()
        composeRule.onNodeWithContentDescription(
            "${EntryDetailCopy.YOU_LABEL}:",
            substring = true,
        ).assertExists()
    }

    @Test
    fun `tags are displayed under the TAGS eyebrow`() {
        val id = createCompleted("got tags")
        entryStore.completeEntry(id, resolved("tired", "monday"), null)
        setDetail(id)
        // Tags sit at the bottom of the scroll region — existence is the contract.
        composeRule.onNodeWithTag("entry_tags").assertExists()
        composeRule.onNodeWithContentDescription("tag: monday").assertExists()
    }

    @Test
    fun `not-found copy shown for unknown entry id`() {
        composeRule.setContent {
            VestigeTheme { EntryDetailScreen(viewModel = buildVm(99_999L), onBack = {}) }
        }
        composeRule.onNodeWithText(EntryDetailCopy.NOT_FOUND).assertIsDisplayed()
    }

    @Test
    fun `back affordance has a click action and fires onBack`() {
        val id = createCompleted("back fires")
        var backFired = false
        composeRule.setContent {
            VestigeTheme { EntryDetailScreen(viewModel = buildVm(id), onBack = { backFired = true }) }
        }
        composeRule.onNodeWithContentDescription(EntryDetailCopy.BACK_CD)
            .assertHasClickAction()
            .performClick()
        assertTrue(backFired)
    }

    @Test
    fun `bottom nav reports the selected tab`() {
        val id = createCompleted("nav test")
        var picked: dev.anchildress1.vestige.ui.components.BottomTab? = null
        composeRule.setContent {
            VestigeTheme {
                EntryDetailScreen(viewModel = buildVm(id), onBack = {}, onNavSelect = { picked = it })
            }
        }
        composeRule.onNodeWithText("PATTERNS").performClick()
        assertTrue(picked == dev.anchildress1.vestige.ui.components.BottomTab.PATTERNS)
    }

    // --- helpers ---

    private fun setDetail(id: Long) {
        composeRule.setContent {
            VestigeTheme { EntryDetailScreen(viewModel = buildVm(id), onBack = {}) }
        }
    }

    private fun buildVm(id: Long) = EntryDetailViewModel(
        entryId = id,
        entryStore = entryStore,
        zoneId = zone,
        ioDispatcher = dispatcher,
    )

    private fun createCompleted(text: String, followUpText: String? = null, persona: Persona = Persona.WITNESS): Long {
        val id = entryStore.createPendingEntry(
            entryText = text,
            timestamp = FIXTURE_INSTANT,
            followUpText = followUpText,
            persona = persona,
        )
        entryStore.completeEntry(id, ResolvedExtraction(emptyMap()), null)
        return id
    }

    private fun resolved(vararg tags: String): ResolvedExtraction = ResolvedExtraction(
        mapOf(
            "tags" to dev.anchildress1.vestige.model.ResolvedField(
                tags.toList(),
                dev.anchildress1.vestige.model.ConfidenceVerdict.CANONICAL,
            ),
        ),
    )

    companion object {
        private val FIXTURE_INSTANT: Instant = Instant.ofEpochSecond(1_778_829_684L)
    }
}
