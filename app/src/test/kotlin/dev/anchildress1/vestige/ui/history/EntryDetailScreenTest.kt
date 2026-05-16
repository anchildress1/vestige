package dev.anchildress1.vestige.ui.history

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.anchildress1.vestige.model.EntryObservation
import dev.anchildress1.vestige.model.ObservationEvidence
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.model.ResolvedExtraction
import dev.anchildress1.vestige.model.TemplateLabel
import dev.anchildress1.vestige.storage.EntryEntity
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

    // --- content display ---

    @Test
    fun `entry number heading is displayed`() {
        val id = createCompleted("crash and burn")
        setDetail(id)

        composeRule.onNodeWithTag("entry_number").assertIsDisplayed()
    }

    @Test
    fun `transcription text is displayed`() {
        val id = createCompleted("standup was brutal today")
        setDetail(id)

        composeRule.onNodeWithTag("entry_transcription").assertIsDisplayed()
        composeRule.onNodeWithText("standup was brutal today").assertIsDisplayed()
    }

    @Test
    fun `follow-up text is displayed as the saved model turn`() {
        val id = createCompleted(
            text = "standup was brutal today",
            followUpText = "What did you do right after it ended?",
            persona = Persona.HARDASS,
        )
        setDetail(id)

        composeRule.onNodeWithTag("entry_follow_up").assertIsDisplayed()
        composeRule.onNodeWithText("What did you do right after it ended?").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("HARDASS: What did you do right after it ended?")
            .assertIsDisplayed()
    }

    @Test
    fun `blank transcription shows dash placeholder`() {
        val id = createCompleted("initial text")
        val box = boxStore.boxFor(EntryEntity::class.java)
        val entity = box.get(id)
        entity.entryText = ""
        box.put(entity)
        setDetail(id)

        composeRule.onNodeWithTag("entry_transcription").assertIsDisplayed()
        // Blank body maps to "—" in display; content description captures the raw body.
        composeRule.onNodeWithContentDescription(
            "${EntryDetailCopy.YOU_LABEL}:",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun `energy descriptor is displayed in reading card`() {
        val id = createCompleted("cruisy and crashed")
        completeWithEnergy(id, "cruisy in, crashed out")
        setDetail(id)

        composeRule.onNodeWithTag("entry_reading_card").assertIsDisplayed()
        // descriptor is uppercased in the composable
        composeRule.onNodeWithText("CRUISY IN, CRASHED OUT").assertIsDisplayed()
    }

    @Test
    fun `template label pill shown when label present`() {
        val id = createCompleted("aftermath entry")
        entryStore.completeEntry(id, ResolvedExtraction(emptyMap()), TemplateLabel.AFTERMATH)
        setDetail(id)

        composeRule.onNodeWithTag("entry_template_label").assertIsDisplayed()
    }

    @Test
    fun `tags are displayed as pills`() {
        val id = createCompleted("got tags")
        entryStore.completeEntry(
            id,
            resolved("tired", "monday"),
            null,
        )
        setDetail(id)

        composeRule.onNodeWithTag("entry_tags").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("tag: monday").assertIsDisplayed()
    }

    @Test
    fun `observations are shown inside reading card`() {
        val id = createCompleted("observation entry")
        val obs = listOf(
            EntryObservation("You said fine twice.", ObservationEvidence.VOCABULARY_CONTRADICTION, emptyList()),
        )
        entryStore.completeEntry(id, ResolvedExtraction(emptyMap()), null, obs)
        setDetail(id)

        composeRule.onNodeWithText("You said fine twice.").assertIsDisplayed()
    }

    // --- not found ---

    @Test
    fun `not-found copy shown for unknown entry id`() {
        composeRule.setContent {
            dev.anchildress1.vestige.ui.theme.VestigeTheme {
                EntryDetailScreen(
                    viewModel = buildVm(99_999L),
                    onBack = {},
                    onNewEntry = {},
                )
            }
        }
        composeRule.onNodeWithText(EntryDetailCopy.NOT_FOUND).assertIsDisplayed()
    }

    // --- bottom bar a11y ---

    @Test
    fun `back button has click action`() {
        val id = createCompleted("back test")
        setDetail(id)

        composeRule.onNodeWithContentDescription(EntryDetailCopy.BACK_CD).assertHasClickAction()
    }

    @Test
    fun `new entry button has click action`() {
        val id = createCompleted("new entry test")
        setDetail(id)

        composeRule.onNodeWithContentDescription(EntryDetailCopy.NEW_ENTRY_CD).assertHasClickAction()
    }

    @Test
    fun `back callback fires on back tap`() {
        val id = createCompleted("back fires")
        var backFired = false
        composeRule.setContent {
            dev.anchildress1.vestige.ui.theme.VestigeTheme {
                EntryDetailScreen(
                    viewModel = buildVm(id),
                    onBack = { backFired = true },
                    onNewEntry = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription(EntryDetailCopy.BACK_CD).performClick()
        assertTrue(backFired)
    }

    @Test
    fun `new entry callback fires on new entry tap`() {
        val id = createCompleted("new entry fires")
        var newEntryFired = false
        composeRule.setContent {
            dev.anchildress1.vestige.ui.theme.VestigeTheme {
                EntryDetailScreen(
                    viewModel = buildVm(id),
                    onBack = {},
                    onNewEntry = { newEntryFired = true },
                )
            }
        }
        composeRule.onNodeWithContentDescription(EntryDetailCopy.NEW_ENTRY_CD).performClick()
        assertTrue(newEntryFired)
    }

    @Test
    fun `source-link highlight cue appears when requested`() {
        val id = createCompleted("pattern source entry")
        composeRule.setContent {
            dev.anchildress1.vestige.ui.theme.VestigeTheme {
                EntryDetailScreen(
                    viewModel = buildVm(id),
                    onBack = {},
                    onNewEntry = {},
                    highlightOnOpen = true,
                )
            }
        }

        composeRule.onNodeWithTag("entry_source_highlight").assertIsDisplayed()
    }

    // --- a11y: stat ribbon ---

    @Test
    fun `stat ribbon has merged content description`() {
        val id = createCompleted("four words here test")
        setDetail(id)
        // The StatRibbon merges descendants; its a11y content covers audio + words
        composeRule.onNodeWithContentDescription(
            "— audio, 4 words",
            substring = true,
        ).assertIsDisplayed()
    }

    // --- a11y: reading card content description ---

    @Test
    fun `reading card content description includes observation text when energy is absent`() {
        val id = createCompleted("observation only")
        val obs = listOf(
            EntryObservation("You said fine twice.", ObservationEvidence.VOCABULARY_CONTRADICTION, emptyList()),
        )
        entryStore.completeEntry(id, ResolvedExtraction(emptyMap()), null, obs)
        setDetail(id)

        // Regression guard: a manual mergeDescendants contentDescription replaces descendant
        // text, so observation lines must be folded into it — not left unspoken.
        composeRule.onNodeWithContentDescription(
            "READING: You said fine twice.",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun `reading card content description spans energy and observations`() {
        val id = createCompleted("energy and observation")
        val resolved = ResolvedExtraction(
            mapOf(
                "energy_descriptor" to dev.anchildress1.vestige.model.ResolvedField(
                    "cruisy in, crashed out",
                    dev.anchildress1.vestige.model.ConfidenceVerdict.CANONICAL,
                ),
            ),
        )
        val obs = listOf(
            EntryObservation("You said fine twice.", ObservationEvidence.VOCABULARY_CONTRADICTION, emptyList()),
        )
        entryStore.completeEntry(id, resolved, null, obs)
        setDetail(id)

        composeRule.onNodeWithContentDescription(
            "READING: cruisy in, crashed out. You said fine twice.",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun `reading card is absent when there is no energy or observation`() {
        val id = createCompleted("plain entry")
        setDetail(id)

        composeRule.onAllNodesWithTag("entry_reading_card").assertCountEquals(0)
    }

    @Test
    fun `follow-up block is absent when no follow-up was saved`() {
        val id = createCompleted("no follow up here")
        setDetail(id)

        composeRule.onAllNodesWithTag("entry_follow_up").assertCountEquals(0)
    }

    // --- not-found keeps navigation ---

    @Test
    fun `not-found state still exposes back and new entry controls`() {
        composeRule.setContent {
            dev.anchildress1.vestige.ui.theme.VestigeTheme {
                EntryDetailScreen(
                    viewModel = buildVm(99_999L),
                    onBack = {},
                    onNewEntry = {},
                )
            }
        }

        // Regression guard: NotFound must not consume the whole column and push the bar off.
        composeRule.onNodeWithText(EntryDetailCopy.NOT_FOUND).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(EntryDetailCopy.BACK_CD).assertHasClickAction()
        composeRule.onNodeWithContentDescription(EntryDetailCopy.NEW_ENTRY_CD).assertHasClickAction()
    }

    // --- helpers ---

    private fun setDetail(id: Long) {
        composeRule.setContent {
            dev.anchildress1.vestige.ui.theme.VestigeTheme {
                EntryDetailScreen(
                    viewModel = buildVm(id),
                    onBack = {},
                    onNewEntry = {},
                )
            }
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

    private fun completeWithEnergy(id: Long, energy: String) {
        val resolved = ResolvedExtraction(
            mapOf(
                "energy_descriptor" to dev.anchildress1.vestige.model.ResolvedField(
                    energy,
                    dev.anchildress1.vestige.model.ConfidenceVerdict.CANONICAL,
                ),
            ),
        )
        entryStore.completeEntry(id, resolved, null)
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
