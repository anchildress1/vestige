package dev.anchildress1.vestige.ui.history

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.anchildress1.vestige.model.ConfidenceVerdict
import dev.anchildress1.vestige.model.EntryLensReceipt
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.Lens
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.model.ResolvedExtraction
import dev.anchildress1.vestige.model.ResolvedField
import dev.anchildress1.vestige.model.TemplateLabel
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.storage.PatternStore
import dev.anchildress1.vestige.storage.closeAfterCleaningThreadResources
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
        entryStore = EntryStore(boxStore)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        boxStore.closeAfterCleaningThreadResources()
        cleanupObjectBoxTempRoot(tempRoot, dataDir)
    }

    @Test
    fun `date hero and transcription are displayed`() {
        val id = createCompleted("standup was brutal today")
        setDetail(id)
        composeRule.onNodeWithTag("entry_time").assertIsDisplayed()
        composeRule.onNodeWithText("7:21 AM").assertIsDisplayed()
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
        val id = entryStore.createPendingEntry("receipt fixture transcript", FIXTURE_INSTANT)
        entryStore.completeEntry(
            id,
            ResolvedExtraction(
                mapOf(
                    "tags" to ResolvedField(
                        listOf("meeting", "battery-died"),
                        ConfidenceVerdict.CANONICAL_WITH_CONFLICT,
                    ),
                ),
            ),
            null,
            lensReceipts = listOf(
                EntryLensReceipt(
                    lens = Lens.LITERAL,
                    extracted = true,
                    fields = mapOf("tags" to "literal receipt"),
                ),
                EntryLensReceipt(
                    lens = Lens.INFERENTIAL,
                    extracted = true,
                    fields = mapOf("tags" to "inferential receipt"),
                ),
                EntryLensReceipt(
                    lens = Lens.SKEPTICAL,
                    extracted = true,
                    fields = mapOf("tags" to "skeptical receipt"),
                    flags = listOf("commitment-without-anchor:not tired:skeptical receipt"),
                ),
            ),
        )
        setDetail(id)
        composeRule.onNodeWithTag("entry_three_lens").assertIsDisplayed()
        composeRule.onNodeWithTag("entry_field_grid").assertIsDisplayed()
        composeRule.onNodeWithText(EntryDetailCopy.THREE_LENS_EYEBROW).assertIsDisplayed()
        composeRule.onNodeWithText("literal receipt").assertIsDisplayed()
        composeRule.onNodeWithText("inferential receipt").assertIsDisplayed()
        composeRule.onNodeWithText("skeptical receipt").assertIsDisplayed()
        composeRule.onNodeWithText(EntryDetailCopy.THREE_LENS_STATUS_CONFLICT).assertIsDisplayed()
        // The extracting/skeleton branch is not the resolved view.
        composeRule.onAllNodesWithTag("entry_extracting").assertCountEquals(0)
    }

    @Test
    fun `picked template shows in the top label slot by display name`() {
        val id = entryStore.createPendingEntry("awake at 3am rearranging the notes app", FIXTURE_INSTANT)
        entryStore.completeEntry(id, ResolvedExtraction(emptyMap()), TemplateLabel.GOBLIN_HOURS)
        setDetail(id)
        composeRule.onNodeWithTag("entry_template_label").assertIsDisplayed()
        composeRule.onNodeWithText("GOBLIN HOURS").assertIsDisplayed()
    }

    @Test
    fun `top label slot is absent when the entry has no template`() {
        val id = entryStore.createPendingEntry("no archetype here", FIXTURE_INSTANT)
        entryStore.completeEntry(id, ResolvedExtraction(emptyMap()), null)
        setDetail(id)
        composeRule.onAllNodesWithTag("entry_template_label").assertCountEquals(0)
    }

    @Test
    fun `field grid shows the resolved vocab tone word`() {
        val id = entryStore.createPendingEntry("drained to the bone by mid-morning", FIXTURE_INSTANT)
        entryStore.completeEntry(
            id,
            ResolvedExtraction(mapOf("vocabulary" to ResolvedField("drained", ConfidenceVerdict.CANONICAL))),
            null,
            lensReceipts = listOf(
                EntryLensReceipt(lens = Lens.LITERAL, extracted = true, fields = mapOf("vocabulary" to "drained")),
            ),
        )
        setDetail(id)
        composeRule.onNodeWithText("VOCAB").assertIsDisplayed()
        composeRule.onAllNodesWithText("drained").onFirst().assertIsDisplayed()
    }

    @Test
    fun `vocab row shows the spread when lenses named different tone words`() {
        val id = entryStore.createPendingEntry("hard to name how this felt", FIXTURE_INSTANT)
        entryStore.completeEntry(
            id,
            ResolvedExtraction(emptyMap()),
            null,
            lensReceipts = listOf(
                EntryLensReceipt(lens = Lens.LITERAL, extracted = true, fields = mapOf("vocabulary" to "tired")),
                EntryLensReceipt(lens = Lens.INFERENTIAL, extracted = true, fields = mapOf("vocabulary" to "wired")),
            ),
        )
        setDetail(id)
        composeRule.onNodeWithText("VOCAB").assertIsDisplayed()
        composeRule.onNodeWithText("tired / wired").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `raw model output is a collapsed accessible disclosure that reveals per-lens text on tap`() {
        val literalRaw = """{"tags":["battery-died"],"template_label":"aftermath"}"""
        val id = entryStore.createPendingEntry("battery died", FIXTURE_INSTANT)
        entryStore.completeEntry(
            id,
            ResolvedExtraction(
                mapOf("tags" to ResolvedField(listOf("battery-died"), ConfidenceVerdict.CANONICAL)),
            ),
            null,
            lensReceipts = listOf(
                EntryLensReceipt(
                    lens = Lens.LITERAL,
                    extracted = true,
                    fields = mapOf("tags" to "battery died"),
                    rawResponse = literalRaw,
                ),
            ),
        )
        setDetail(id)

        val toggle = composeRule.onNodeWithContentDescription(EntryDetailCopy.RAW_OUTPUT_EXPAND_CD)
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        toggle.assertHasClickAction()
        // Collapsed by default — the raw payload is not on screen.
        composeRule.onAllNodesWithText(literalRaw, substring = true).assertCountEquals(0)

        toggle.performClick()

        // Expanded: the verbatim per-lens payload renders as ordinary readable text — the raw
        // blob is not crammed into contentDescription, and the debug panel does not auto-announce
        // via a live region.
        composeRule.onNodeWithText(literalRaw, substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("LITERAL raw output: $literalRaw")
            .assertCountEquals(0)
    }

    @Test
    fun `observations block renders persisted observation text evidence and fields`() {
        val id = entryStore.createPendingEntry("said fine twice", FIXTURE_INSTANT)
        entryStore.completeEntry(
            id,
            ResolvedExtraction(emptyMap()),
            null,
            observations = listOf(
                dev.anchildress1.vestige.model.EntryObservation(
                    text = "You used fine twice.",
                    evidence = dev.anchildress1.vestige.model.ObservationEvidence.THEME_NOTICING,
                    fields = listOf("tags", "recurrence_link"),
                ),
            ),
            lensReceipts = listOf(
                EntryLensReceipt(
                    lens = Lens.LITERAL,
                    extracted = true,
                    fields = mapOf("tags" to listOf("fine")),
                ),
            ),
        )

        setDetail(id)

        composeRule.onNodeWithTag("entry_observations").assertExists()
        composeRule.onNodeWithText("You used fine twice.").assertExists()
        composeRule.onNodeWithText("THEME NOTICING · tags, recurrence_link").assertExists()
    }

    @Test
    fun `observations block is an announced status band with no click action (a11y)`() {
        val id = entryStore.createPendingEntry("fine was said twice", FIXTURE_INSTANT)
        entryStore.completeEntry(
            id,
            ResolvedExtraction(emptyMap()),
            null,
            observations = listOf(
                dev.anchildress1.vestige.model.EntryObservation(
                    text = "You used fine twice.",
                    evidence = dev.anchildress1.vestige.model.ObservationEvidence.THEME_NOTICING,
                    fields = emptyList(),
                ),
            ),
            lensReceipts = emptyList(),
        )
        setDetail(id)
        val band = composeRule.onNodeWithTag("entry_observations")
        band.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        band.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
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
    fun `completed entry with no lens receipts hides the three-lens static shell`() {
        val id = createCompleted("debug fixture without receipts")
        setDetail(id)
        composeRule.onAllNodesWithTag("entry_three_lens").assertCountEquals(0)
        composeRule.onAllNodesWithTag("entry_field_grid").assertCountEquals(0)
        composeRule.onAllNodesWithTag("entry_extracting").assertCountEquals(0)
        composeRule.onNodeWithText("debug fixture without receipts").assertExists()
    }

    @Test
    fun `fresh detail never renders known demo fixture phrases`() {
        val id = createCompleted("just recorded this now")
        setDetail(id)
        composeRule.onAllNodesWithText("Tuesday Meetings", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText(
            "Fourth entry mentions Tuesday meetings. State before: cruising. After: crashed.",
            substring = true,
        ).assertCountEquals(0)
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
        patternStore = PatternStore(boxStore),
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
